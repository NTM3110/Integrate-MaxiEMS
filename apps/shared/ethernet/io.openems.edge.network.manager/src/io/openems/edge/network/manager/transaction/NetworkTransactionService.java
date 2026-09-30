package io.openems.edge.network.manager.transaction;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import io.openems.edge.network.manager.operation.NetworkErrorCode;
import io.openems.edge.network.manager.operation.NetworkInterfaceConfig;
import io.openems.edge.network.manager.operation.NetworkOperationError;
import io.openems.edge.network.manager.operation.NetworkOperationSnapshot;
import io.openems.edge.network.manager.operation.NetworkOperationStage;
import io.openems.edge.network.manager.operation.NetworkOperationState;
import io.openems.edge.network.manager.operation.NetworkOperationStore;
import io.openems.edge.network.manager.os.NetworkSystemAdapter;

public final class NetworkTransactionService {

	private final NetworkOperationStore store;
	private final NetworkSystemAdapter adapter;
	private final Executor executor;
	private final Clock clock;
	private final Supplier<String> operationIdSupplier;
	private final int confirmationTimeoutSeconds;
	private volatile String checkpointId;
	private volatile Instant checkpointDeadline;

	public NetworkTransactionService(NetworkOperationStore store, NetworkSystemAdapter adapter, Executor executor,
			Clock clock, Supplier<String> operationIdSupplier, int confirmationTimeoutSeconds) {
		this.store = store;
		this.adapter = adapter;
		this.executor = executor;
		this.clock = clock;
		this.operationIdSupplier = operationIdSupplier;
		this.confirmationTimeoutSeconds = confirmationTimeoutSeconds;
	}

	public NetworkOperationSnapshot submit(long revision, NetworkInterfaceConfig config, List<String> previousAddresses) {
		var operationId = this.operationIdSupplier.get();
		var snapshot = this.store.create(operationId, revision, config, this.clock.instant(), previousAddresses,
				this.confirmationTimeoutSeconds);
		this.executor.execute(() -> this.apply(operationId));
		return snapshot;
	}

	public NetworkOperationSnapshot get(String operationId) {
		return this.store.get(operationId);
	}

	public synchronized NetworkOperationSnapshot recoverAfterRestart(NetworkOperationSnapshot interrupted) {
		this.store.restore(interrupted);
		if (interrupted.state().isTerminal()) {
			return interrupted;
		}
		if (interrupted.confirmationDeadline() == null
				|| !this.clock.instant().isBefore(interrupted.confirmationDeadline())) {
			return this.store.transition(interrupted.operationId(), NetworkOperationState.ROLLED_BACK,
					this.clock.instant());
		}
		this.checkpointId = interrupted.checkpointId();
		this.checkpointDeadline = interrupted.confirmationDeadline();
		return interrupted;
	}

	public synchronized NetworkOperationSnapshot confirm(String operationId, long expectedSequence) {
		var current = this.store.get(operationId);
		if (current.state() == NetworkOperationState.APPLIED) {
			return current;
		}
		if (current.state() == NetworkOperationState.ROLLED_BACK) {
			throw new IllegalStateException(NetworkErrorCode.OPERATION_ALREADY_ROLLED_BACK.name());
		}
		if (current.sequence() != expectedSequence) {
			throw new IllegalStateException(NetworkErrorCode.STALE_OPERATION_SNAPSHOT.name());
		}
		if (current.state() != NetworkOperationState.AWAITING_CONFIRMATION) {
			throw new IllegalStateException("Operation is not awaiting confirmation");
		}
		try {
			if (this.checkpointId != null) {
				this.adapter.commitCheckpoint(this.checkpointId);
				this.checkpointId = null;
				this.checkpointDeadline = null;
			}
			return this.store.transition(operationId, NetworkOperationState.APPLIED, this.clock.instant());
		} catch (Exception e) {
			throw new IllegalStateException("Failed to commit network checkpoint", e);
		}
	}

	public synchronized NetworkOperationSnapshot cancel(String operationId) {
		var current = this.store.get(operationId);
		if (current.state().isTerminal()) {
			return current;
		}
		if (this.checkpointId == null) {
			return this.store.transition(operationId, NetworkOperationState.CANCELLED, this.clock.instant());
		}
		try {
			this.adapter.rollbackCheckpoint(this.checkpointId);
			this.checkpointId = null;
			this.checkpointDeadline = null;
			return this.store.transition(operationId, NetworkOperationState.ROLLED_BACK, this.clock.instant());
		} catch (Exception e) {
			return this.fail(operationId, NetworkErrorCode.ROLLBACK_FAILED, NetworkOperationStage.ROLLBACK, e, null);
		}
	}

	public synchronized NetworkOperationSnapshot expire(String operationId, Instant now) {
		var current = this.store.get(operationId);
		if (current.state().isTerminal()) {
			return current;
		}
		if (current.confirmationDeadline() == null || now.isBefore(current.confirmationDeadline())) {
			return current;
		}
		this.store.transition(operationId, NetworkOperationState.ROLLING_BACK, now);
		this.checkpointId = null;
		this.checkpointDeadline = null;
		var error = new NetworkOperationError(NetworkErrorCode.CONFIRMATION_TIMEOUT, NetworkOperationStage.ROLLBACK,
				"Network configuration confirmation timed out", false, NetworkOperationState.ROLLED_BACK);
		return this.store.rolledBack(operationId, error, now);
	}

	private synchronized void apply(String operationId) {
		var initial = this.store.get(operationId);
		if (initial.state().isTerminal()) {
			return;
		}
		var config = initial.desired();
		var errorCode = NetworkErrorCode.PROFILE_CREATE_FAILED;
		var stage = NetworkOperationStage.PREPARE;
		try {
			this.store.transition(operationId, NetworkOperationState.APPLYING, this.clock.instant());
			this.checkpointId = this.adapter.createCheckpoint(config.interfaceName(), this.confirmationTimeoutSeconds);
			this.checkpointDeadline = this.clock.instant().plusSeconds(this.confirmationTimeoutSeconds);
			this.store.withCheckpoint(operationId, this.checkpointId, this.checkpointDeadline, this.clock.instant());
			var profile = this.adapter.ensureProfile(config.interfaceName());
			errorCode = NetworkErrorCode.PROFILE_UPDATE_FAILED;
			stage = NetworkOperationStage.APPLY;
			this.adapter.apply(profile, config);
			errorCode = NetworkErrorCode.ACTIVATION_FAILED;
			stage = NetworkOperationStage.ACTIVATE;
			try {
				this.adapter.activate(profile, config.interfaceName());
			} catch (Exception activationError) {
				var inactiveVerification = this.adapter.verify(profile, config);
				if (!inactiveVerification.profileVerified() || inactiveVerification.linkAvailable()) {
					throw activationError;
				}
			}
			this.store.transition(operationId, NetworkOperationState.VERIFYING, this.clock.instant());
			errorCode = NetworkErrorCode.VERIFICATION_FAILED;
			stage = NetworkOperationStage.VERIFY;
			var verification = this.adapter.verify(profile, config);
			if (!verification.profileVerified()) {
				throw new IllegalStateException("Network profile verification failed");
			}
			if (!verification.linkAvailable()) {
				this.adapter.commitCheckpoint(Objects.requireNonNull(this.checkpointId));
				this.checkpointId = null;
				this.checkpointDeadline = null;
			}
			this.store.verified(operationId, true, verification.runtimeVerified(), verification.linkAvailable(),
					this.checkpointDeadline,
					this.clock.instant());
		} catch (Exception e) {
			var rollbackState = this.rollback();
			this.fail(operationId, errorCode, stage, e, rollbackState);
		}
	}

	private NetworkOperationSnapshot fail(String operationId, NetworkErrorCode code, NetworkOperationStage stage,
			Exception cause, NetworkOperationState rollbackState) {
		var error = new NetworkOperationError(code, stage, cause.getMessage() == null ? cause.getClass().getSimpleName()
				: cause.getMessage(), false, rollbackState);
		return this.store.fail(operationId, error, this.clock.instant());
	}

	private NetworkOperationState rollback() {
		if (this.checkpointId == null) {
			return null;
		}
		try {
			this.adapter.rollbackCheckpoint(this.checkpointId);
			return NetworkOperationState.ROLLED_BACK;
		} catch (Exception e) {
			return NetworkOperationState.FAILED;
		} finally {
			this.checkpointId = null;
			this.checkpointDeadline = null;
		}
	}
}
