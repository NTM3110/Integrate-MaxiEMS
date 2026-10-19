using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Lifecycle;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Preflight;
using Maxiems.Deployer.Core.Reporting;
using Maxiems.Deployer.Core.Security;
using Maxiems.Deployer.Infrastructure.Manufacturing.Inspection;
using Maxiems.Deployer.Infrastructure.OpenEms;
using Maxiems.Deployer.Infrastructure.Payload;
using Maxiems.Deployer.Infrastructure.Preflight;
using Maxiems.Deployer.Infrastructure.Ssh;
using Maxiems.Deployer.Infrastructure.Tailscale;

namespace Maxiems.Deployer.Infrastructure.Deployment;

public sealed class DeploymentEngine(
    HostTrustStore trustStore,
    ITailscaleNodeClientFactory? tailscaleFactory = null,
    TimeProvider? timeProvider = null,
    DeploymentEngineOptions? options = null)
{
    private readonly ITailscaleNodeClientFactory tailscaleFactory =
        tailscaleFactory ?? new SshTailscaleNodeClientFactory();
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;
    private readonly DeploymentEngineOptions options = ValidateOptions(options);

    public async Task<DeploymentExecutionResult> RunAsync(
        DeploymentExecutionRequest request,
        Action<DeploymentExecutionEvent>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);

        var password = request.AdministratorPassword.ToString();
        var bootstrapPassword = request.BootstrapPassword.ToString();
        var passphrase = request.PrivateKeyPassphrase.ToString();
        var redactor = new SecretRedactor(
        [
            new("privateKeyPath", request.PrivateKeyPath),
            new("password", password),
            new("bootstrapPassword", bootstrapPassword),
            new("passphrase", passphrase),
        ]);

        var verification = PayloadVerifier.VerifyArchive(request.PayloadPath);
        if (!verification.IsValid || string.IsNullOrWhiteSpace(verification.ReleaseId))
        {
            throw new InvalidDataException("Embedded deployment payload verification failed.");
        }

        var releaseId = new ReleaseId(verification.ReleaseId);
        var run = new DeploymentRunBuilder(releaseId, redactor, progress, this.clock);
        var payloadHash = await DeploymentResultWriter
            .HashAsync(request.PayloadPath, cancellationToken)
            .ConfigureAwait(false);

        ValidateManufacturingReview(request, releaseId, payloadHash);

        var state = new DeploymentSessionState();
        run.AddCheck("kiosk-mode", true, request.KioskMode == DeploymentKioskMode.Preserve
            ? "Preserve the target's existing kiosk configuration and service."
            : "Manage the MAXiEMS Chromium kiosk configuration and service.");

        try
        {
            var manifest = await ValidateInputAsync(request, run).ConfigureAwait(false);
            var target = await PrepareTargetAsync(request, manifest, run, state, cancellationToken)
                .ConfigureAwait(false);

            await ConnectRestrictedAsync(request, run, state, cancellationToken).ConfigureAwait(false);

            var releasePlan = await InstallAndStageReleaseAsync(
                request,
                releaseId,
                target,
                run,
                state,
                cancellationToken).ConfigureAwait(false);

            await ActivateAndStartAsync(
                request,
                releaseId,
                releasePlan,
                run,
                state,
                cancellationToken).ConfigureAwait(false);

            await ConfigureTailscaleAsync(
                request,
                state.RequireRestricted(),
                run,
                cancellationToken).ConfigureAwait(false);

            state.Api = new DeploymentApiSession(
                state.RequireRestricted(),
                request.AdministratorPassword.Span);

            state.ComponentCheckpoint = await ReconcileAsync(
                manifest,
                state.Api,
                state.RequireOperations(),
                run,
                cancellationToken).ConfigureAwait(false);

            await VerifyAndCommitAsync(
                request,
                releaseId,
                manifest,
                run,
                state,
                cancellationToken).ConfigureAwait(false);

            AddSuccessChecks(run, releasePlan);

            return await DeploymentResultWriter.FinishAsync(
                run,
                DeploymentFinalState.Succeeded,
                payloadHash,
                request.ReportsDirectory,
                manufacturing: request.ManufacturingReport)
                .ConfigureAwait(false);
        }
        catch (Exception exception)
        {
            return await RecoverAndReportAsync(
                request,
                releaseId,
                payloadHash,
                exception,
                run,
                state)
                .ConfigureAwait(false);
        }
        finally
        {
            await state.DisposeAsync().ConfigureAwait(false);
        }
    }

    private static Task<ComponentManifest?> ValidateInputAsync(
        DeploymentExecutionRequest request,
        DeploymentRunBuilder run)
    {
        return run.PhaseAsync(
            DeploymentPhase.ValidateInput,
            () => Task.FromResult(Validate(request)));
    }

    private async Task<UbuntuPreflightSnapshot> PrepareTargetAsync(
        DeploymentExecutionRequest request,
        ComponentManifest? manifest,
        DeploymentRunBuilder run,
        DeploymentSessionState state,
        CancellationToken cancellationToken)
    {
        var bootstrapOptions = Options(
            request,
            request.BootstrapUsername,
            allowBootstrapPassword: true);

        var bootstrap = await run.PhaseAsync(
            DeploymentPhase.ConnectBootstrap,
            () => SshBootstrapSession.ConnectAsync(
                bootstrapOptions,
                trustStore,
                cancellationToken)).ConfigureAwait(false);
        state.Bootstrap = bootstrap;

        var target = await RunPreflightAsync(
            request,
            manifest,
            run,
            bootstrap,
            cancellationToken).ConfigureAwait(false);

        await ProvisionIdentityAsync(
            request,
            run,
            bootstrap,
            cancellationToken).ConfigureAwait(false);

        await bootstrap.DisposeAsync().ConfigureAwait(false);
        state.Bootstrap = null;

        return target;
    }

    private async Task<UbuntuPreflightSnapshot> RunPreflightAsync(
        DeploymentExecutionRequest request,
        ComponentManifest? manifest,
        DeploymentRunBuilder run,
        SshBootstrapSession bootstrap,
        CancellationToken cancellationToken)
    {
        var snapshot = await run.PhaseAsync(DeploymentPhase.Preflight, async () =>
        {
            var collected = await new UbuntuPreflightCollector(bootstrap)
                .CollectAsync(cancellationToken)
                .ConfigureAwait(false);

            var localTime = this.clock.GetUtcNow();
            var evaluation = UbuntuPreflightEvaluator.Evaluate(
                collected,
                localTime,
                ManifestRequiresModbus(manifest));

            foreach (var warning in evaluation.Warnings)
            {
                run.Warning(DeploymentPhase.Preflight, "preflight." + warning.Code,
                    $"{warning.Code}: target clock {warning.Subject} differs from deployer clock "
                    + $"{localTime:O} by more than {UbuntuPreflightEvaluator.MaximumClockSkew.TotalMinutes:0} minutes. "
                    + "Deployment continues; check NTP and timestamps on the target.");
            }

            if (!evaluation.IsSupported)
            {
                var issues = evaluation.Issues.Select(issue => $"{issue.Code}:{issue.Subject}");
                throw new InvalidOperationException(
                    "Target preflight failed: " + string.Join(", ", issues));
            }

            return collected;
        }).ConfigureAwait(false);

        run.AddVersion("ubuntu", snapshot.VersionId);
        run.AddVersion("architecture", snapshot.Architecture);

        if (request.InspectedIdentity is { } expectedIdentity)
        {
            await run.PhaseAsync(
                DeploymentPhase.Preflight,
                () => new TargetIdentityGuard(bootstrap)
                    .VerifyAsync(expectedIdentity, cancellationToken),
                "Re-verified permanent target identity immediately before deployment.")
                .ConfigureAwait(false);
        }

        return snapshot;
    }

    private static Task ProvisionIdentityAsync(
        DeploymentExecutionRequest request,
        DeploymentRunBuilder run,
        SshBootstrapSession bootstrap,
        CancellationToken cancellationToken)
    {
        return run.PhaseAsync(DeploymentPhase.ProvisionIdentity, async () =>
        {
            var publicKey = await File
                .ReadAllTextAsync(request.PrivateKeyPath + ".pub", cancellationToken)
                .ConfigureAwait(false);

            publicKey = publicKey.Trim();
            if (!SshPublicKeyValidator.IsValid(publicKey))
            {
                throw new InvalidDataException("SSH public key is invalid.");
            }

            var assets = BootstrapPayloadAssets.Read(request.PayloadPath, publicKey);
            await bootstrap
                .InstallAsync(assets, publicKey, localBootstrap: request.LocalBootstrap,
                    cancellationToken)
                .ConfigureAwait(false);
        });
    }

    private async Task ConnectRestrictedAsync(
        DeploymentExecutionRequest request,
        DeploymentRunBuilder run,
        DeploymentSessionState state,
        CancellationToken cancellationToken)
    {
        state.Restricted = await run.PhaseAsync(
            DeploymentPhase.ReconnectRestricted,
            async () =>
            {
                var session = await SshNetTransportSession
                    .ConnectAsync(
                        Options(request, "maxiems-deploy"),
                        trustStore,
                        cancellationToken)
                    .ConfigureAwait(false);

                var identity = await new RestrictedIdentityVerifier(session)
                    .VerifyAsync(cancellationToken)
                    .ConfigureAwait(false);

                if (identity.IsValid)
                {
                    return session;
                }

                await session.DisposeAsync().ConfigureAwait(false);
                throw new InvalidOperationException(
                    "Restricted deployment identity verification failed.");
            }).ConfigureAwait(false);

        state.ResetRemoteClients();
    }

    private static async Task<ReleasePlan> InstallAndStageReleaseAsync(
        DeploymentExecutionRequest request,
        ReleaseId releaseId,
        UbuntuPreflightSnapshot target,
        DeploymentRunBuilder run,
        DeploymentSessionState state,
        CancellationToken cancellationToken)
    {
        var operations = state.RequireOperations();

        await run.PhaseAsync(
            DeploymentPhase.InstallPrerequisites,
            () => operations.RunAsync(
                "install-prerequisites",
                cancellationToken: cancellationToken)).ConfigureAwait(false);

        state.PreviousRelease = await operations
            .RunAsync("current-release", cancellationToken: cancellationToken)
            .ConfigureAwait(false);

        var redeploy = state.PreviousRelease.Equals(releaseId.Value, StringComparison.Ordinal);
        var quiesceLegacy = ShouldQuiesceLegacy(
            target.LegacyOpenEmsActive,
            target.LegacyOpenEmsEnabled);
        var releasePlan = new ReleasePlan(redeploy, quiesceLegacy);

        await run.PhaseAsync(DeploymentPhase.StageRelease, async () =>
        {
            if (!RequiresReleaseTransaction(redeploy, quiesceLegacy))
            {
                return;
            }

            var transaction = state.RequireTransaction();
            await transaction.BeginAsync(releaseId, cancellationToken).ConfigureAwait(false);
            state.TransactionActive = true;

            if (quiesceLegacy)
            {
                await operations
                    .RunAsync("quiesce-legacy", [releaseId.Value], cancellationToken)
                    .ConfigureAwait(false);
            }

            if (redeploy)
            {
                return;
            }

            await using var payload = File.OpenRead(request.PayloadPath);
            await transaction
                .StageAsync(releaseId, payload, cancellationToken)
                .ConfigureAwait(false);
        }).ConfigureAwait(false);

        var verificationMessage = redeploy
            ? "Verified existing release; payload upload was safely skipped."
            : null;

        await run.PhaseAsync(
            DeploymentPhase.VerifyRemotePayload,
            () => Task.CompletedTask,
            verificationMessage).ConfigureAwait(false);

        return releasePlan;
    }

    private static async Task ActivateAndStartAsync(
        DeploymentExecutionRequest request,
        ReleaseId releaseId,
        ReleasePlan releasePlan,
        DeploymentRunBuilder run,
        DeploymentSessionState state,
        CancellationToken cancellationToken)
    {
        var restricted = state.RequireRestricted();
        var operations = state.RequireOperations();

        await run.PhaseAsync(DeploymentPhase.ActivateRelease, async () =>
        {
            if (!releasePlan.Redeploy)
            {
                await state.RequireTransaction()
                    .ActivateAsync(releaseId, cancellationToken)
                    .ConfigureAwait(false);
            }

            await operations
                .RunAsync("runtime-config", [request.KioskMode.ToString().ToLowerInvariant()],
                    cancellationToken)
                .ConfigureAwait(false);

            var bootstrapStore = new SshOpenEmsBootstrapStore(
                restricted,
                restricted,
                TimeSpan.FromMinutes(2));
            var bootstrapCoordinator = new OpenEmsBootstrapCoordinator(bootstrapStore);

            await bootstrapCoordinator
                .EnsureAsync(request.AdministratorPassword, cancellationToken)
                .ConfigureAwait(false);

            await operations
                .RunAsync("bms-setup", [request.TargetProfile.ToString().ToLowerInvariant()],
                    cancellationToken)
                .ConfigureAwait(false);
        }).ConfigureAwait(false);

        await run.PhaseAsync(DeploymentPhase.StartServices, async () =>
        {
            await operations
                .RunAsync("service", ["restart"], cancellationToken)
                .ConfigureAwait(false);
            await operations
                .RunAsync("nginx", ["reload"], cancellationToken)
                .ConfigureAwait(false);
        }).ConfigureAwait(false);
    }

    private async Task VerifyAndCommitAsync(
        DeploymentExecutionRequest request,
        ReleaseId releaseId,
        ComponentManifest? manifest,
        DeploymentRunBuilder run,
        DeploymentSessionState state,
        CancellationToken cancellationToken)
    {
        await run.PhaseAsync(DeploymentPhase.HealthCheck, async () =>
        {
            await VerifyRuntimeAsync(
                manifest,
                verifyDefaultLogin: true,
                state,
                cancellationToken).ConfigureAwait(false);

            if (request.RequireRebootAcceptance)
            {
                await RebootAndVerifyAsync(
                    request,
                    run,
                    state,
                    cancellationToken).ConfigureAwait(false);
            }

            if (state.TransactionActive)
            {
                await state.RequireTransaction()
                    .CommitAsync(releaseId, cancellationToken)
                    .ConfigureAwait(false);
                state.TransactionActive = false;
            }
        }).ConfigureAwait(false);
    }

    private static async Task VerifyRuntimeAsync(
        ComponentManifest? manifest,
        bool verifyDefaultLogin,
        DeploymentSessionState state,
        CancellationToken cancellationToken)
    {
        var operations = state.RequireOperations();
        var restricted = state.RequireRestricted();
        var api = state.RequireApi();

        for (var attempt = 0; attempt < 3; attempt++)
        {
            try
            {
                await operations
                    .RunAsync("status", cancellationToken: cancellationToken)
                    .ConfigureAwait(false);
                break;
            }
            catch (IOException exception) when (attempt < 2 &&
                (exception.Message.Contains("public port", StringComparison.Ordinal) ||
                 exception.Message.Contains("IPv4-reachable wildcard listener", StringComparison.Ordinal)))
            {
                await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken).ConfigureAwait(false);
            }
        }

        var bmsState = await operations
            .RunAsync("bms-ready", cancellationToken: cancellationToken)
            .ConfigureAwait(false);

        await BmsReadinessProbe
            .WaitAsync(
                restricted,
                verifyDefaultLogin && bmsState == "fresh",
                cancellationToken)
            .ConfigureAwait(false);

        await new OpenEmsReadinessProbe(api.Client)
            .WaitAsync(
                TimeSpan.FromMinutes(3),
                TimeSpan.FromSeconds(2),
                cancellationToken)
            .ConfigureAwait(false);

        if (ManifestRequiresModbus(manifest))
        {
            await DeploymentApiSession
                .ProbeModbusAsync(restricted, cancellationToken)
                .ConfigureAwait(false);
        }
    }

    private async Task RebootAndVerifyAsync(
        DeploymentExecutionRequest request,
        DeploymentRunBuilder run,
        DeploymentSessionState state,
        CancellationToken cancellationToken)
    {
        await state.RequireOperations()
            .RunAsync("reboot", cancellationToken: cancellationToken)
            .ConfigureAwait(false);

        state.DisposeApi();
        await state.DisposeRestrictedAsync().ConfigureAwait(false);

        state.Restricted = await this
            .ReconnectAfterRebootAsync(request, cancellationToken)
            .ConfigureAwait(false);
        state.ResetRemoteClients();
        state.Api = new DeploymentApiSession(
            state.RequireRestricted(),
            request.AdministratorPassword.Span);

        await VerifyRuntimeAsync(
            manifest: null,
            verifyDefaultLogin: false,
            state,
            cancellationToken).ConfigureAwait(false);

        run.AddCheck(
            "reboot-persistence",
            true,
            "Reconnected with verified host identity and repeated service/API checks after reboot.");
    }

    private async Task<DeploymentExecutionResult> RecoverAndReportAsync(
        DeploymentExecutionRequest request,
        ReleaseId releaseId,
        string payloadHash,
        Exception exception,
        DeploymentRunBuilder run,
        DeploymentSessionState state)
    {
        var recoveryTransportFailure = await EnsureRecoveryConnectionAsync(request, state)
            .ConfigureAwait(false);

        await DeploymentFailureDiagnostics
            .CollectAsync(state.Operations, run)
            .ConfigureAwait(false);

        var retainForInspection = false;
        if (request.KeepFailedReleaseForInspection && state.TransactionActive &&
            run.LastFailedPhase == DeploymentPhase.HealthCheck && state.Operations is not null)
        {
            try
            {
                var transactionStatus = await state.Operations.RunAsync(
                    "transaction-status", [releaseId.Value], CancellationToken.None).ConfigureAwait(false);
                var currentRelease = await state.Operations.RunAsync(
                    "current-release", cancellationToken: CancellationToken.None).ConfigureAwait(false);
                retainForInspection = transactionStatus == "activated" && currentRelease == releaseId.Value;
            }
            catch (Exception inspectionError)
            {
                run.FailureDiagnostics("Could not verify the active release for inspection: " +
                    inspectionError.Message);
            }
        }
        if (retainForInspection)
        {
            run.FailureDiagnostics($"Release {releaseId} remains active for inspection. " +
                "The deployment transaction remains open; rollback it on the target before another deployment.");
            run.AddCheck("failure", false, exception.Message);
            run.AddCheck("retained-for-inspection", false,
                $"Release {releaseId} remains active with an open deployment transaction.");
            return await DeploymentResultWriter.FinishAsync(
                run,
                DeploymentFinalState.Failed,
                payloadHash,
                request.ReportsDirectory,
                manufacturing: request.ManufacturingReport).ConfigureAwait(false);
        }

        var recovery = await DeploymentFailureRecovery.RecoverAsync(
            releaseId,
            state.PreviousRelease,
            state.TransactionActive,
            state.Transaction,
            state.ComponentCheckpoint,
            state.Api,
            state.Operations,
            run,
            recoveryTransportFailure).ConfigureAwait(false);

        run.AddCheck("failure", false, exception.Message);

        return await DeploymentResultWriter.FinishAsync(
            run,
            recovery.State,
            payloadHash,
            request.ReportsDirectory,
            recovery.Audit,
            request.ManufacturingReport).ConfigureAwait(false);
    }

    private async Task<Exception?> EnsureRecoveryConnectionAsync(
        DeploymentExecutionRequest request,
        DeploymentSessionState state)
    {
        if (!RequiresRecoveryConnection(
            state.TransactionActive,
            state.ComponentCheckpoint is not null))
        {
            return null;
        }

        if (state.Restricted is not null)
        {
            try
            {
                await state.RequireOperations()
                    .RunAsync("current-release", cancellationToken: CancellationToken.None)
                    .ConfigureAwait(false);
                return null;
            }
            catch
            {
                state.DisposeApi();
                await state.DisposeRestrictedAsync().ConfigureAwait(false);
            }
        }

        try
        {
            state.Restricted = await SshNetTransportSession.ConnectAsync(
                Options(request, "maxiems-deploy"),
                trustStore,
                CancellationToken.None).ConfigureAwait(false);
            state.ResetRemoteClients();

            if (state.ComponentCheckpoint is not null)
            {
                state.Api = new DeploymentApiSession(
                    state.RequireRestricted(),
                    request.AdministratorPassword.Span);
            }

            return null;
        }
        catch (Exception reconnectException)
        {
            state.Operations = null;
            state.Transaction = null;
            return reconnectException;
        }
    }

    private static void AddSuccessChecks(
        DeploymentRunBuilder run,
        ReleasePlan releasePlan)
    {
        run.AddCheck(
            "service-active-enabled",
            true,
            "MAXiEMS Edge service is active and enabled.");
        run.AddCheck(
            "nginx-ui",
            true,
            "nginx is active/enabled and the local UI endpoint responded.");
        run.AddCheck(
            "bms-ready",
            true,
            "BMS schema/authentication ready; fresh installation login verified.");
        run.AddCheck(
            "openems-authenticated",
            true,
            "Authenticated OpenEMS readiness completed.");
        run.AddCheck(
            "binding-policy",
            true,
            "Felix and BMS WebSocket are public; management REST and optional Modbus bindings match policy.");
        run.AddCheck(
            "release-integrity",
            true,
            releasePlan.Redeploy
                ? "Verified idempotent release and file hashes."
                : "Activated release and verified installed file hashes.");

        if (releasePlan.QuiesceLegacy)
        {
            run.AddCheck(
                "legacy-disabled",
                true,
                "Stopped and disabled legacy openems.service with transaction-backed rollback.");
        }
    }

    private async Task<SshNetTransportSession> ReconnectAfterRebootAsync(DeploymentExecutionRequest request,
        CancellationToken cancellationToken)
    {
        await Task.Delay(TimeSpan.FromSeconds(3), cancellationToken).ConfigureAwait(false);
        var deadline = this.clock.GetUtcNow().AddMinutes(3);
        Exception? lastFailure = null;
        while (this.clock.GetUtcNow() < deadline)
        {
            try
            {
                var session = await SshNetTransportSession.ConnectAsync(Options(request, "maxiems-deploy"), trustStore,
                    cancellationToken).ConfigureAwait(false);
                var identity = await new RestrictedIdentityVerifier(session).VerifyAsync(cancellationToken)
                    .ConfigureAwait(false);
                if (identity.IsValid)
                    return session;
                await session.DisposeAsync().ConfigureAwait(false);
                lastFailure = new InvalidOperationException("Restricted identity was invalid after reboot.");
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                lastFailure = exception;
            }
            await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken).ConfigureAwait(false);
        }
        throw new TimeoutException("Target did not return with its verified deployment identity after reboot.", lastFailure);
    }

    private static ComponentManifest? Validate(DeploymentExecutionRequest request)
    {
        if (!File.Exists(request.PrivateKeyPath) || !File.Exists(request.PrivateKeyPath + ".pub"))
        {
            throw new FileNotFoundException("SSH private/public key pair was not found.");
        }
        OpenEmsManagementPassword.RequireNonEmpty(request.AdministratorPassword.Span,
            nameof(request.AdministratorPassword));
        BootstrapPayloadAssets.ValidateCompatibility(request.PayloadPath);
        return PayloadComponentManifestResolver.Resolve(request.PayloadPath, request.ComponentManifestPath);
    }

    private static void ValidateManufacturingReview(DeploymentExecutionRequest request, ReleaseId releaseId,
        string payloadHash)
    {
        if (request.ManufacturingContext is null && request.InspectedIdentity is null)
            return;
        if (request.ManufacturingContext is not { } context || request.InspectedIdentity is not { } identity)
        {
            throw new InvalidDataException("Manufacturing deployment requires both reviewed context and target identity.");
        }
        if (context.Action == DeploymentActionKind.BlockedConflict)
        {
            throw new InvalidOperationException("A blocked deployment plan cannot cross the mutation boundary.");
        }
        if (context.Release.ReleaseId != releaseId
            || !context.Release.PayloadSha256.Equals(payloadHash, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("Selected release changed after review; review the deployment again.");
        }
        if (!context.InspectionSha256.Equals(identity.InspectionSha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("Profile or inspection changed after review; inspect the device again.");
        }
        if (request.ManufacturingReport is not { } report || report.DeviceId != context.DeviceId
            || report.Serial != context.Serial || report.Profile != context.Profile
            || report.InspectionId != context.InspectionId
            || !report.InspectionSha256.Equals(context.InspectionSha256, StringComparison.OrdinalIgnoreCase)
            || report.Release.ReleaseId != context.Release.ReleaseId
            || !report.Release.PayloadSha256.Equals(context.Release.PayloadSha256, StringComparison.OrdinalIgnoreCase)
            || !report.Release.ArtifactSha256.Equals(context.Release.ArtifactSha256, StringComparison.OrdinalIgnoreCase)
            || report.PlannedAction != context.Action
            || report.Endpoint != request.Endpoint)
        {
            throw new InvalidDataException("Manufacturing report context changed after review; review the deployment again.");
        }
    }

    internal static bool ManifestRequiresModbus(ComponentManifest? manifest) => manifest?.Components.Any(component =>
        component.FactoryPid.Contains("ModbusTcp", StringComparison.OrdinalIgnoreCase)
        || component.Properties.Any(property => property.Name.Equals("port", StringComparison.Ordinal)
            && property.Value is ComponentNumberValue { Value: 502 })) == true;

    internal static bool ShouldQuiesceLegacy(bool active, bool enabled) => active || enabled;

    internal static bool RequiresReleaseTransaction(bool redeploy, bool quiesceLegacy) => !redeploy || quiesceLegacy;

    internal static bool RequiresRecoveryConnection(
        bool transactionActive,
        bool hasComponentCheckpoint)
    {
        return transactionActive || hasComponentCheckpoint;
    }

    private static SshTransportOptions Options(
        DeploymentExecutionRequest request,
        string username,
        bool allowBootstrapPassword = false) => new(
        request.Endpoint, username, allowBootstrapPassword ? request.BootstrapPassword : ReadOnlyMemory<char>.Empty,
        request.PrivateKeyPath, request.PrivateKeyPassphrase,
        TimeSpan.FromSeconds(20), TimeSpan.FromMinutes(15));

    private async Task ConfigureTailscaleAsync(
        DeploymentExecutionRequest request,
        SshNetTransportSession session,
        DeploymentRunBuilder run,
        CancellationToken cancellationToken)
    {
        await run.PhaseAsync(DeploymentPhase.ConfigureTailscale, async () =>
        {
            if (!request.ConfigureTailscale)
                return;
            var coordinator = new TailscaleLoginCoordinator(this.tailscaleFactory.Create(session), this.clock);
            var result = await coordinator.LoginAsync(this.options.TailscaleTimeout, this.options.TailscalePollInterval,
                uri => run.TailscaleUri(DeploymentPhase.ConfigureTailscale, uri), cancellationToken).ConfigureAwait(false);
            if (!result.IsAuthenticated)
                throw new TimeoutException("Tailscale authentication timed out.");
        }).ConfigureAwait(false);
    }

    private static DeploymentEngineOptions ValidateOptions(DeploymentEngineOptions? options)
    {
        var value = options ?? DeploymentEngineOptions.Default;
        value.Validate();
        return value;
    }

    private static async Task<OpenEmsManagedCheckpoint?> ReconcileAsync(
        ComponentManifest? manifest,
        DeploymentApiSession api,
        DeploymentRemoteOperations operations,
        DeploymentRunBuilder run,
        CancellationToken cancellationToken)
    {
        OpenEmsManagedCheckpoint? checkpoint = null;
        await run.PhaseAsync(DeploymentPhase.ReconcileComponents, async () =>
        {
            await new OpenEmsReadinessProbe(api.Client).WaitAsync(TimeSpan.FromMinutes(3), TimeSpan.FromSeconds(2),
                cancellationToken).ConfigureAwait(false);
            if (manifest is null)
                return;
            var recoveryRestarted = false;
            var reconciler = new OpenEmsComponentReconciler(api.Client);
            checkpoint = await reconciler.CaptureAsync(manifest, cancellationToken).ConfigureAwait(false);
            var result = await reconciler.ReconcileAsync(manifest, async token =>
            {
                recoveryRestarted = true;
                await operations.RunAsync("service", ["restart"], token).ConfigureAwait(false);
                await new OpenEmsReadinessProbe(api.Client).WaitAsync(TimeSpan.FromMinutes(3), TimeSpan.FromSeconds(2), token)
                    .ConfigureAwait(false);
            }, cancellationToken).ConfigureAwait(false);
            var created = result.Components.Count(component => component.Action == ComponentUpsertAction.Created);
            var migrated = result.Components.Count(component => component.Action == ComponentUpsertAction.Updated);
            var unchanged = result.Components.Count(component => component.Action == ComponentUpsertAction.Unchanged);
            var skipped = result.Components.Count(component => component.Action == ComponentUpsertAction.SkippedMissing);
            run.AddCheck("components", true,
                $"Reconciled {result.Components.Length} component(s): {created} created, {migrated} migrated, "
                + $"{unchanged} unchanged, {skipped} update-only missing.");
            if (result.Components.All(component => component.Action is ComponentUpsertAction.Unchanged
                or ComponentUpsertAction.SkippedMissing))
                return;
            if (!recoveryRestarted)
            {
                await operations.RunAsync("service", ["restart"], cancellationToken).ConfigureAwait(false);
                await new OpenEmsReadinessProbe(api.Client).WaitAsync(TimeSpan.FromMinutes(3), TimeSpan.FromSeconds(2),
                    cancellationToken).ConfigureAwait(false);
            }
            await new OpenEmsComponentReconciler(api.Client).ReconcileAsync(manifest, cancellationToken).ConfigureAwait(false);
        }).ConfigureAwait(false);
        return checkpoint;
    }

    private sealed record ReleasePlan(
        bool Redeploy,
        bool QuiesceLegacy);

    private sealed class DeploymentSessionState : IAsyncDisposable
    {
        public SshBootstrapSession? Bootstrap
        {
            get; set;
        }

        public SshNetTransportSession? Restricted
        {
            get; set;
        }

        public DeploymentApiSession? Api
        {
            get; set;
        }

        public SshReleaseTransactionClient? Transaction
        {
            get; set;
        }

        public DeploymentRemoteOperations? Operations
        {
            get; set;
        }

        public OpenEmsManagedCheckpoint? ComponentCheckpoint
        {
            get; set;
        }

        public bool TransactionActive
        {
            get; set;
        }

        public string? PreviousRelease
        {
            get; set;
        }

        public void ResetRemoteClients()
        {
            var restricted = this.RequireRestricted();
            this.Operations = new DeploymentRemoteOperations(
                restricted,
                TimeSpan.FromMinutes(15));
            this.Transaction = new SshReleaseTransactionClient(
                restricted,
                restricted,
                TimeSpan.FromMinutes(15));
        }

        public SshNetTransportSession RequireRestricted()
        {
            return this.Restricted
                ?? throw new InvalidOperationException("Restricted deployment session is not connected.");
        }

        public DeploymentApiSession RequireApi()
        {
            return this.Api
                ?? throw new InvalidOperationException("Deployment API session is not connected.");
        }

        public DeploymentRemoteOperations RequireOperations()
        {
            return this.Operations
                ?? throw new InvalidOperationException("Remote deployment operations are not initialized.");
        }

        public SshReleaseTransactionClient RequireTransaction()
        {
            return this.Transaction
                ?? throw new InvalidOperationException("Release transaction client is not initialized.");
        }

        public void DisposeApi()
        {
            this.Api?.Dispose();
            this.Api = null;
        }

        public async ValueTask DisposeRestrictedAsync()
        {
            if (this.Restricted is null)
            {
                return;
            }

            await this.Restricted.DisposeAsync().ConfigureAwait(false);
            this.Restricted = null;
            this.Operations = null;
            this.Transaction = null;
        }

        public async ValueTask DisposeAsync()
        {
            this.DisposeApi();
            await this.DisposeRestrictedAsync().ConfigureAwait(false);

            if (this.Bootstrap is not null)
            {
                await this.Bootstrap.DisposeAsync().ConfigureAwait(false);
                this.Bootstrap = null;
            }
        }
    }

}
