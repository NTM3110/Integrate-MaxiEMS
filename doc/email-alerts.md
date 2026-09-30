# Email alerts

Email alerts use the organisation's SMTP relay; MaxiEMS does not host a mail
server. A `PLATFORM_ADMIN` configures the global relay under **Email Alerts**.
The password is write-only: leave the field empty when editing settings to
retain the stored password.

For development, run Mailpit on the developer workstation and configure the
appliance with that workstation's LAN IP, SMTP port `1025`, **None (Mailpit)**
security, and no credentials. Inspect captured messages at port `8025` on the
workstation. Mailpit is a development capture service, not a production relay.

For production, use the organisation-approved hostname, port, TLS mode
(`STARTTLS` or direct `TLS` as specified by the relay), sender identity, and
credentials. Use **Send test email** after saving; failures intentionally name
only the settings to inspect and never expose relay transcripts or credentials.
