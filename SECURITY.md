# Security Notes

Scan The Planet's OAuth sign-in uses the system browser and the IntelliJ built-in loopback server
(`http://127.0.0.1`) for the callback. Access, refresh, and ID tokens are stored in
IntelliJ PasswordSafe, and are never written to plaintext settings files. The plugin
does not send telemetry.
