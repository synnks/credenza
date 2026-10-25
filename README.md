# Credenza

Credenza is a local AWS credential broker for development tools.
The goal is to reuse an existing AWS login across Docker builds, containers, and IDEs, with credential caching and refresh handled in one place.

## Credential Resolution

The current implementation obtains temporary AWS role credentials from an existing AWS CLI SSO login.
It selects a named profile, resolves its SSO session, and uses that session's cached login to request credentials for the configured account and role.

Callers receive role credentials with their actual expiration; SSO access and refresh tokens remain internal to source handling.
Expired credentials are rejected, and errors distinguish configuration problems, unusable logins, and service failures.
Credential values and raw configuration content are excluded from diagnostics.

## Supported Sources

The built-in source supports AWS profiles that reference named SSO sessions and their exact AWS CLI cache records.
It respects `AWS_CONFIG_FILE` and keeps the SSO session's region separate from the profile's service region.
Other credential-provider configurations are rejected explicitly.

Resolution reads existing logins without modifying the AWS cache.
An expired login requires reauthentication.
