# Backend HTTP service

M5 exposes the server forecast boundary through a minimal Ktor transport.

## Endpoints

- `POST /v1/forecast` — versioned forecast contract from `:backend:contract`.
- `GET /health/live` — process liveness.
- `GET /health/ready` — readiness after production composition has successfully started.

The forecast endpoint accepts only JSON and only the canonical privacy-reduced integer-tenths target coordinate. Provider credentials, raw provider payloads and verification evidence are never accepted from the client.

## Transport bounds

The service applies:

- a 4 KiB default forecast request-body limit, with a hard configuration ceiling of 64 KiB;
- a default limit of 32 concurrent forecast executions, with fail-fast overload rejection;
- a 120-second default request execution timeout, with a five-minute hard configuration ceiling;
- request-coroutine cancellation when a Netty client disconnects;
- fixed public error payloads that do not expose stack traces, provider payloads or provider secrets.

Provider/network/cache bounds remain owned by the lower backend modules.

## Runtime configuration

The standalone JVM process reads only non-secret transport/runtime configuration from environment variables:

- `METEOONE_SERVER_NATIVE_BUNDLE` — **required** absolute or relative path to the verified Linux x86_64 server-native bundle.
- `METEOONE_SERVER_HOST` — optional bind address; defaults to `127.0.0.1`.
- `METEOONE_SERVER_PORT` — optional TCP port; defaults to `8080`.

The loopback default is intentional: external exposure should be an explicit deployment decision, normally behind a TLS reverse proxy or other managed ingress.

Provider secrets, if a future adapter requires them, belong to server/provider composition and must not be passed through this HTTP contract.
