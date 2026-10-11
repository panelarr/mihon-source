# Security policy

## Reporting a vulnerability

Please report vulnerabilities **privately** through GitHub: open this repository's **Security** tab and choose **Report a vulnerability** (GitHub private vulnerability reporting). Don't open a public issue.

Include what you found, how to reproduce it and what impact you expect. We'll acknowledge the report, keep you updated and credit you in the advisory unless you'd rather stay anonymous.

## Supported versions

mihon-source is in early development. Only the latest commit on `main` gets fixes.

## Threat model in short

- **Extensions are untrusted code.** They run inside the service's JVM, each in its own classloader. Only install extensions you trust. Verify signing fingerprints, and pin store signing keys.
- **Secrets:** the admin token and client API keys are stored hashed. Proxy credentials are stored encrypted. None of them are returned by the API once set, and none are logged.
- Every endpoint except the health check needs a token.
