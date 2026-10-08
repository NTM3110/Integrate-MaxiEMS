using System.Collections.Immutable;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security;
using System.Security.Cryptography;
using System.Text.Json;
using Maxiems.Deployer.App.ViewModels;
using Maxiems.Deployer.App.ViewModels.Operations;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Infrastructure.Deployment;
using Maxiems.Deployer.Infrastructure.Manufacturing.Inspection;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;
using Maxiems.Deployer.Infrastructure.Payload;
using Maxiems.Deployer.Infrastructure.Ssh;

namespace Maxiems.Deployer.App.Services;

public sealed class ProductionManufacturingWorkflowBackend(
    HostTrustStore trustStore,
    SqliteManufacturingStore store,
    IWizardSshIdentityProvider identityProvider,
    IDeploymentPayloadProvider payloadProvider,
    string reportsDirectory,
    TimeProvider? timeProvider = null) : IManufacturingWorkflowBackend
{
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;
    private readonly DeviceRepository devices = new(store);
    private readonly InspectionRepository inspections = new(store);
    private readonly DeploymentRunRepository runs = new(store);

    public async Task<ManufacturingInspectionDraft> InspectAsync(ManufacturingInspectRequest request,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        var release = await this.ResolveReleaseAsync(request.Profile, cancellationToken).ConfigureAwait(false);
        using var identity = identityProvider.GetOrCreate();
        var password = Copy(request.BootstrapPassword);
        var passphrase = Copy(identity.PrivateKeyPassphrase);
        try
        {
            var connection = new TargetInspectionConnection(request.Endpoint, request.Username, password,
                identity.PrivateKeyPath, passphrase);
            var inspection = await new TargetInspectionService(
                new SshTargetInspectionSessionFactory(trustStore), this.clock)
                .InspectAsync(connection, release, cancellationToken).ConfigureAwait(false);
            return new(inspection, request.Profile, release, request.Username);
        }
        finally
        {
            Zero(password);
            Zero(passphrase);
        }
    }

    public async Task<ManufacturingReviewBundle> ReviewAsync(ManufacturingInspectionDraft draft,
        EthernetMappingConfirmation mapping, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(draft);
        ArgumentNullException.ThrowIfNull(mapping);
        var preliminary = ManufacturingInspectionEvaluator.Evaluate(draft.ReadOnlyInspection.Hardware,
            draft.Profile, mapping, new(false, false));
        var serial = preliminary.DerivedSerial
            ?? throw new InvalidOperationException("A serial number could not be derived from the confirmed LAN mapping.");
        var existing = await this.devices.FindBySerialAsync(serial, cancellationToken).ConfigureAwait(false);
        var sourceLabel = draft.Profile.EthernetPorts.Single(port => port.IsSerialSource).PhysicalLabel;
        var source = FindPort(draft, mapping, sourceLabel);
        var conflict = await this.devices.FindConflictAsync(serial, source.PermanentMac!, existing?.DeviceId,
            cancellationToken).ConfigureAwait(false);
        var evaluation = ManufacturingInspectionEvaluator.Evaluate(draft.ReadOnlyInspection.Hardware,
            draft.Profile, mapping, conflict);
        if (existing is not null)
            EnsureExistingIdentity(existing, draft.ReadOnlyInspection.Hardware);
        return this.BuildReview(draft, mapping, evaluation, existing);
    }

    public async Task<ManufacturingDeployResult> DeployAsync(ManufacturingReviewBundle review,
        SecureString bootstrapPassword, SecureString administratorPassword,
        Action<WizardProgressUpdate>? progress = null, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(review);
        if (!review.IsReady)
            throw new InvalidOperationException("A blocked manufacturing review cannot be deployed.");
        await this.PersistReviewedDeviceAsync(review, cancellationToken).ConfigureAwait(false);
        var device = await this.devices.FindBySerialAsync(review.Device.Serial, cancellationToken).ConfigureAwait(false)
            ?? throw new InvalidOperationException("Reviewed device registration was not persisted.");
        device = await this.MoveToDeployingAsync(device, cancellationToken).ConfigureAwait(false);
        string? payload = null;
        try
        {
            payload = await payloadProvider.MaterializeAsync(cancellationToken).ConfigureAwait(false);
            using var identity = identityProvider.GetOrCreate();
            var bootstrap = Copy(bootstrapPassword);
            var administrator = Copy(administratorPassword);
            var passphrase = Copy(identity.PrivateKeyPassphrase);
            try
            {
                var request = new DeploymentExecutionRequest(review.ReportContext.Endpoint, review.BootstrapUsername,
                    bootstrap, identity.PrivateKeyPath, passphrase, administrator, payload, null,
                    review.ConfigureTailscale, reportsDirectory)
                {
                    ManufacturingContext = review.DeploymentContext,
                    InspectedIdentity = review.IdentityExpectation,
                    ManufacturingReport = review.ReportContext,
                    RequireRebootAcceptance = review.RequireRebootAcceptance,
                };
                var result = await new DeploymentEngine(trustStore, timeProvider: this.clock).RunAsync(request,
                    eventValue => progress?.Invoke(new(eventValue.Phase, eventValue.Outcome, eventValue.Timestamp,
                        eventValue.OperatorMessage, eventValue.TailscaleLoginUri?.AbsoluteUri)), cancellationToken)
                    .ConfigureAwait(false);
                await this.CompleteRunAsync(review, result, device, cancellationToken).ConfigureAwait(false);
                return new(result.Record.FinalState, result.Record.DeploymentId, result.Reports,
                    Summary(review.ActionPlan.Action, result.Record.FinalState));
            }
            finally
            {
                Zero(bootstrap);
                Zero(administrator);
                Zero(passphrase);
            }
        }
        catch
        {
            await this.TryMoveToReworkAsync(device, CancellationToken.None).ConfigureAwait(false);
            throw;
        }
        finally
        {
            if (payload is not null && File.Exists(payload))
                File.Delete(payload);
        }
    }

    private ManufacturingReviewBundle BuildReview(ManufacturingInspectionDraft draft,
        EthernetMappingConfirmation mapping, ManufacturingInspectionEvaluation evaluation, DeviceRecord? existing)
    {
        var now = this.clock.GetUtcNow();
        var serial = evaluation.DerivedSerial!;
        var ports = draft.Profile.EthernetPorts.Select(requirement =>
        {
            var observed = FindPort(draft, mapping, requirement.PhysicalLabel);
            return new DeviceEthernetPortRecord(requirement.IsSerialSource ? "WAN0" : "WAN1",
                observed.LinuxName, observed.PermanentMac!,
                observed.CurrentMac, requirement.IsSerialSource, draft.ReadOnlyInspection.Hardware.CollectedAt);
        }).OrderBy(port => port.PhysicalLabel, StringComparer.Ordinal).ToImmutableArray();
        var device = existing ?? new(Guid.NewGuid(), serial, DeviceLifecycleState.Registered, draft.Profile.Reference,
            ports, null, null, null, null, now, now);
        var inspection = new TargetInspection(draft.ReadOnlyInspection.InspectionId,
            draft.ReadOnlyInspection.Endpoint, draft.Profile.Reference, draft.ReadOnlyInspection.Hardware,
            evaluation.Checklist, serial, evaluation.SnapshotSha256, draft.ReadOnlyInspection.StartedAt,
            draft.ReadOnlyInspection.CompletedAt);
        var action = ManufacturingDeploymentActionPlanner.Plan(new(draft.ReadOnlyInspection.Installation, draft.Release,
            draft.ReadOnlyInspection.SelectedReleaseCompatible, false, false, false, null));
        var engineer = mapping.Engineer;
        var context = new ManufacturingDeploymentContext(device.DeviceId, serial, draft.Profile.Reference,
            inspection.InspectionId, inspection.SnapshotSha256, draft.Release, action.Action, engineer);
        var identity = new TargetIdentityExpectation(draft.ReadOnlyInspection.SnapshotSha256,
            ports.Select(port => port.PermanentMac).OrderBy(mac => mac.Normalized, StringComparer.Ordinal).ToImmutableArray());
        var report = new ManufacturingReportContext(device.DeviceId, serial, ports, draft.Profile.Reference,
            inspection.InspectionId, inspection.SnapshotSha256, draft.ReadOnlyInspection.SnapshotSha256,
            inspection.Checklist, action.Action, action.Action, draft.Release, engineer, inspection.Endpoint,
            Assembly.GetExecutingAssembly().GetName().Version?.ToString() ?? "unknown", null,
            TimeZoneInfo.Local.Id);
        return new(inspection, device, existing is null, action, context, identity, report,
            draft.BootstrapUsername, draft.Profile.ConfigureTailscale, draft.Profile.RequireRebootAcceptance);
    }

    private async Task<LocalReleaseDescriptor> ResolveReleaseAsync(ManufacturingProfile profile,
        CancellationToken cancellationToken)
    {
        string? path = null;
        try
        {
            path = await payloadProvider.MaterializeAsync(cancellationToken).ConfigureAwait(false);
            var verification = PayloadVerifier.VerifyArchive(path);
            if (!verification.IsValid || verification.ReleaseId is not { } releaseId
                || verification.PayloadSha256 is not { } payloadSha256
                || !ReleaseApproval.MatchesVerifiedPayload(profile.DefaultReleaseId, new(releaseId), payloadSha256))
            {
                throw new InvalidDataException("The embedded verified release does not match the approved profile.");
            }
            var embeddedRelease = new ReleaseId(releaseId);
            await using var stream = File.OpenRead(path);
            var hash = Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false))
                .ToLowerInvariant();
            var architectures = profile.Cpu.Architectures.Select(value => value.ToLowerInvariant() switch
            {
                "amd64" or "x86_64" => SupportedArchitecture.Amd64,
                "arm64" or "aarch64" => SupportedArchitecture.Arm64,
                _ => throw new InvalidDataException($"Unsupported profile architecture '{value}'."),
            }).Distinct().ToImmutableArray();
            return new(embeddedRelease, embeddedRelease.Value, LocalReleaseSource.Embedded,
                LocalReleaseStatus.Embedded, hash, hash, profile.OperatingSystem.Versions, architectures, "1.0.0");
        }
        finally
        {
            if (path is not null && File.Exists(path))
                File.Delete(path);
        }
    }

    private async Task PersistReviewedDeviceAsync(ManufacturingReviewBundle review, CancellationToken cancellationToken)
    {
        if (review.IsNewDevice)
            await this.devices.RegisterAsync(review.Device, "read-only hardware inspection",
            cancellationToken).ConfigureAwait(false);
        await this.inspections.AppendAsync(new(review.Inspection, review.Device.DeviceId,
            JsonSerializer.Serialize(review.Inspection.Hardware), review.ReportContext.Engineer), cancellationToken)
            .ConfigureAwait(false);
    }

    private async Task<DeviceRecord> MoveToDeployingAsync(DeviceRecord device, CancellationToken cancellationToken)
    {
        var state = device.State;
        if (state == DeviceLifecycleState.DeploymentVerified)
        {
            await this.devices.UpdateLifecycleAsync(device.DeviceId, state, DeviceLifecycleState.ReworkRequired,
                this.clock.GetUtcNow(), cancellationToken).ConfigureAwait(false);
            state = DeviceLifecycleState.ReworkRequired;
        }
        if (state is DeviceLifecycleState.Registered or DeviceLifecycleState.ReworkRequired
            or DeviceLifecycleState.ReadyForWarehouse)
        {
            await this.devices.UpdateLifecycleAsync(device.DeviceId, state, DeviceLifecycleState.Inspecting,
                this.clock.GetUtcNow(), cancellationToken).ConfigureAwait(false);
            state = DeviceLifecycleState.Inspecting;
        }
        if (state == DeviceLifecycleState.Inspecting)
        {
            await this.devices.UpdateLifecycleAsync(device.DeviceId, state, DeviceLifecycleState.ReadyForDeployment,
                this.clock.GetUtcNow(), cancellationToken).ConfigureAwait(false);
            state = DeviceLifecycleState.ReadyForDeployment;
        }
        await this.devices.UpdateLifecycleAsync(device.DeviceId, state, DeviceLifecycleState.Deploying,
            this.clock.GetUtcNow(), cancellationToken).ConfigureAwait(false);
        return device with
        {
            State = DeviceLifecycleState.Deploying,
            UpdatedAt = this.clock.GetUtcNow()
        };
    }

    private async Task CompleteRunAsync(ManufacturingReviewBundle review, DeploymentExecutionResult result,
        DeviceRecord device, CancellationToken cancellationToken)
    {
        var acceptance = result.Record.FinalState == DeploymentFinalState.Succeeded
            ? ManufacturingWarehouseAcceptance.Evaluate(result.Record, review.ActionPlan.Action,
                review.RequireRebootAcceptance) : null;
        var next = result.Record.FinalState == DeploymentFinalState.Succeeded
            ? DeviceLifecycleState.DeploymentVerified : DeviceLifecycleState.ReworkRequired;
        await this.devices.UpdateLifecycleAsync(device.DeviceId, DeviceLifecycleState.Deploying, next,
            this.clock.GetUtcNow(), cancellationToken).ConfigureAwait(false);
        if (acceptance is { IsReady: true })
        {
            await this.devices.UpdateLifecycleAsync(device.DeviceId, DeviceLifecycleState.DeploymentVerified,
                DeviceLifecycleState.ReadyForWarehouse, this.clock.GetUtcNow(), cancellationToken).ConfigureAwait(false);
        }
        else if (acceptance is { IsReady: false })
        {
            await this.devices.UpdateLifecycleAsync(device.DeviceId, DeviceLifecycleState.DeploymentVerified,
                DeviceLifecycleState.ReworkRequired, this.clock.GetUtcNow(), cancellationToken).ConfigureAwait(false);
        }
        await this.runs.AppendAsync(new(result.Record.DeploymentId, device.DeviceId, review.Inspection.InspectionId,
            review.ReportContext.Endpoint, review.DeploymentContext.Release.ReleaseId, review.ActionPlan.Action,
            result.Record.FinalState, result.Record.StartedAt, result.Record.CompletedAt, result.Reports,
            review.ReportContext.Engineer)
        {
            PlannedAction = review.ActionPlan.Action,
            ObservedAction = review.ActionPlan.Action,
        }, cancellationToken).ConfigureAwait(false);
    }

    private async Task TryMoveToReworkAsync(DeviceRecord device, CancellationToken cancellationToken)
    {
        try
        {
            await this.devices.UpdateLifecycleAsync(device.DeviceId, DeviceLifecycleState.Deploying,
                DeviceLifecycleState.ReworkRequired, this.clock.GetUtcNow(), cancellationToken).ConfigureAwait(false);
        }
        catch (InvalidOperationException)
        {
        }
    }

    private static EthernetPortInventory FindPort(ManufacturingInspectionDraft draft,
        EthernetMappingConfirmation mapping, string physicalLabel) => draft.ReadOnlyInspection.Hardware.EthernetPorts
        .Single(port => port.LinuxName == mapping.PhysicalToLinuxName[physicalLabel]);

    private static void EnsureExistingIdentity(DeviceRecord existing, HardwareInventorySnapshot hardware)
    {
        var expected = existing.EthernetPorts.Select(port => port.PermanentMac.Normalized)
            .OrderBy(value => value, StringComparer.Ordinal);
        var observed = hardware.EthernetPorts.Where(port => port.PermanentMac is not null)
            .Select(port => port.PermanentMac!.Normalized).OrderBy(value => value, StringComparer.Ordinal);
        if (!expected.SequenceEqual(observed, StringComparer.Ordinal))
        {
            throw new InvalidOperationException("The inspected Ethernet identity does not match the registered device.");
        }
    }

    private static char[] Copy(SecureString value)
    {
        var pointer = Marshal.SecureStringToGlobalAllocUnicode(value);
        try
        {
            var result = new char[value.Length];
            Marshal.Copy(pointer, result, 0, result.Length);
            return result;
        }
        finally
        {
            Marshal.ZeroFreeGlobalAllocUnicode(pointer);
        }
    }

    private static void Zero(char[] value) => CryptographicOperations.ZeroMemory(
        MemoryMarshal.AsBytes(value.AsSpan()));
    private static string Summary(DeploymentActionKind action, DeploymentFinalState state) => state switch
    {
        DeploymentFinalState.Succeeded when action == DeploymentActionKind.NoChange =>
            "The installed release already matched the verified payload and the device is ready for warehouse intake.",
        DeploymentFinalState.Succeeded => $"{action} completed; automated acceptance passed and the device is ready for warehouse intake.",
        DeploymentFinalState.RolledBack => "Deployment failed and the previous release was restored. Rework is required.",
        DeploymentFinalState.Cancelled => "Deployment was cancelled safely. Rework is required.",
        _ => "Deployment failed. Review the report before retrying.",
    };
}
