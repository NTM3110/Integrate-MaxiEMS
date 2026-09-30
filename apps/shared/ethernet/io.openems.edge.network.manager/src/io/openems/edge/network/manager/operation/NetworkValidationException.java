package io.openems.edge.network.manager.operation;

public final class NetworkValidationException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;
	private final NetworkErrorCode code;

	public NetworkValidationException(NetworkErrorCode code, String message) {
		super(message);
		this.code = code;
	}

	public NetworkErrorCode code() {
		return this.code;
	}
}
