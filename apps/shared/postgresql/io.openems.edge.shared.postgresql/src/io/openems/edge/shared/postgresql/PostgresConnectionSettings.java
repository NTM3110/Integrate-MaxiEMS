package io.openems.edge.shared.postgresql;

/** Pool configuration. The password is deliberately omitted from toString(). */
public record PostgresConnectionSettings(String id, String host, int port, String database, String user,
		String password, int poolSize) {

	public PostgresConnectionSettings {
		if (isBlank(id) || isBlank(host) || isBlank(database) || isBlank(user)) {
			throw new IllegalArgumentException("PostgreSQL id, host, database and user are required");
		}
		if (port < 1 || port > 65535 || poolSize < 1) {
			throw new IllegalArgumentException("Invalid PostgreSQL port or pool size");
		}
		password = password == null ? "" : password;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	@Override
	public String toString() {
		return "PostgresConnectionSettings[id=" + this.id + ", host=" + this.host + ", port=" + this.port
				+ ", database=" + this.database + ", user=" + this.user + ", password=<redacted>, poolSize="
				+ this.poolSize + "]";
	}
}
