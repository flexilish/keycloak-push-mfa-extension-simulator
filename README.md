# Keycloak push mfa extension simulator

Simulator for Keycloak push MFA Extension

## Quick Start

### Local Development

```bash
# Start the Spring Boot application
mvn spring-boot:run

# In another terminal, start TypeScript watch mode (optional, for live reloading)
npm run dev
```

The application will be available at `http://localhost:5000/mock`

### Docker

```bash
# Build the Docker image
docker build -t push-mfa-extension-simulator .

# Run the container
docker run -p 5000:5000 push-mfa-extension-simulator

# Open in browser: http://localhost:5000/mock
```

## Toolchain & Build

### Prerequisites

- **Java 21**: For running Spring Boot application
- **Node.js 20.11.1**: Automatically installed and managed by Maven frontend plugin
- **Maven 3.8+**: For building the project

### Build Tools & Workflow

#### TypeScript & Bundling

- **TypeScript 5.0+**: Type-safe JavaScript development
- **ESBuild 0.27.1**: Ultra-fast bundler for ES modules
- **Build output**: `npm run build` compiles TS to `src/main/resources/static/js/`

#### Code Quality

- **ESLint 8.57**: TypeScript linting with `@typescript-eslint` parser
- **Prettier 3.2.5**: Code formatting
- **Commands**:
  - `npm run lint` - Check for issues
  - `npm run lint:fix` - Auto-fix ESLint errors
  - `npm run format` - Format code with Prettier
  - `npm run format:check` - Verify formatting compliance

#### Complete Build Workflow

```bash
# Development: watch mode TypeScript compilation
npm run dev

# Production build
npm run build

# Linting & formatting
npm run lint:fix
npm run format

# Maven build (includes TypeScript build)
mvn clean package

# Run tests & checks (after AGENTS.md workflow)
mvn spotless:apply
mvn verify
```

## Architecture & CORS

### The Problem: Frontend + Backend on Same Host

When running the simulator mock and Keycloak on the same host, you encounter **CORS (Cross-Origin Resource Sharing)** restrictions:

1. **Keycloak** typically runs on port `8080` (e.g., `http://localhost:8080/realms/demo`)
2. **Mock simulator** runs on port `5000` (e.g., `http://localhost:5000/mock`)
3. **Different ports = different origins** → CORS blocks frontend requests

#### CORS Error Example

```
Access to XMLHttpRequest at 'http://localhost:8080/realms/demo/...'
from origin 'http://localhost:5000' has been blocked by CORS policy
```

### Solution: Reverse Proxy with Nginx

Use an **nginx reverse proxy** to serve both Keycloak and the mock simulator under the **same host** and **same port** (443/HTTPS), eliminating CORS issues.

#### Architecture

```
Client (Browser)
    ↓
https://myapp.local (nginx on port 443)
    ├→ /mock → http://host.docker.internal:5000/mock (mock simulator)
    └→ /realms → http://host.docker.internal:8080 (Keycloak)
```

All requests come from the same origin (`https://myapp.local`), so CORS is not triggered.

## Reverse Proxy Setup with Nginx

### Prerequisites

1. **SSL Certificates**: You need valid certificates for `myapp.local`

   ```bash
   # Example: Create a self-signed cert (for testing only)
   mkdir -p /mnt/c/certs/myapp
   cd /mnt/c/certs/myapp
   openssl req -x509 -newkey rsa:4096 -keyout myapp.local-key.pem -out myapp.local.pem -days 365 -nodes
   ```

2. **Hosts file entry**: Add `myapp.local` to your hosts file

   ```
   127.0.0.1 myapp.local
   ```

3. **Docker network**: Ensure docker can reach your host services

### Running the Nginx Proxy

```bash
# Stop and remove any existing container
docker rm -f myapp-local-nginx 2>/dev/null || true

# Start nginx with the provided config
docker run --name myapp-local-nginx \
  -p 80:80 -p 443:443 \
  --add-host=host.docker.internal:host-gateway \
  -v "$(pwd)/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  -v "/mnt/c/certs/myapp:/etc/nginx/certs:ro" \
  nginx:alpine
```

### Key Proxy Features (nginx.conf)

#### HTTP to HTTPS Redirect

```nginx
# Port 80 → 443 (HTTP to HTTPS)
server {
  listen 80;
  server_name myapp.local;
  return 301 https://$host$request_uri;
}
```

#### Mock Simulator Proxy

```nginx
location /mock {
  proxy_pass http://host.docker.internal:5000/mock/;
  proxy_http_version 1.1;

  # Forward client headers for proper request context
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
  proxy_set_header X-Forwarded-Prefix /mock;
}

# Handle /mock without trailing slash
location = /mock {
  return 301 /mock/;
}
```

#### Keycloak Proxy

```nginx
location / {
  proxy_pass http://host.docker.internal:8080;
  proxy_http_version 1.1;

  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-Proto https;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Host $host;
  proxy_set_header X-Forwarded-Port 443;
}
```

### Complete Local Setup (Docker Compose Alternative)

```bash
# 1. Start Keycloak
docker run --name keycloak \
  -p 8080:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  keycloak:latest start-dev

# 2. Start mock simulator
mvn spring-boot:run  # or docker run the image

# 3. Start nginx proxy
docker run --name myapp-local-nginx \
  -p 80:80 -p 443:443 \
  --add-host=host.docker.internal:host-gateway \
  -v "$(pwd)/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  -v "/mnt/c/certs/myapp:/etc/nginx/certs:ro" \
  nginx:alpine

# 4. Access via: https://myapp.local
```

### Testing the Proxy Configuration

```bash
# Test mock endpoint
curl -k https://myapp.local/mock

# Test Keycloak endpoint
curl -k https://myapp.local/realms/demo

# Check headers are properly forwarded
curl -k -v https://myapp.local/mock/info
```

### Using a proxy for communication between the mock and keycloak

Add the following properties to the application.yaml to tell the mocks Rest Template to use the http proxy with the specified host and port:

### application.yaml

```yaml
proxy:
  http:
    host: <your-proxy-host>
    port: <your-proxy-port>
```

### Example Enrollment Backend Call using mitmprox

1. Spin up the mitmprox
```bash
sudo docker run --rm -it -p 3128:8080 mitmproxy/mitmproxy mitmproxy --mode regular
```

2. Configure Spring to use the proxy by using the default gateway IP 172.17.0.1 of the Docker bridge network (docker0). Add the following properties to the application.yaml:
```yaml
proxy:
  http:
    host: 172.17.0.1
    port: 3128
```

3. Rebuild the application
```bash
sudo docker build -t push-mfa-extension-simulator .
sudo docker run -p 5000:5000 -p 5005:5005 push-mfa-extension-simulator
```
4. In the PushMFA-Simulator UI on http://localhost:5000/mock/enroll change the URL IdentityManagement from http://localhost:8080/realms/demo to http://172.17.0.1:8080/realms/demo and click Enroll.
5. Verify the logs of the running nitmproxy instance

## Troubleshooting

### Network & Connectivity Issues

| Issue                       | Cause                         | Solution                                                                                                       |
| --------------------------- | ----------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `myapp.local` not resolving | Hosts file not updated        | Add `127.0.0.1 myapp.local` to `/etc/hosts` (Linux/macOS) or `C:\Windows\System32\drivers\etc\hosts` (Windows) |
| Cannot reach host services  | Docker networking issue       | Verify `--add-host=host.docker.internal:host-gateway` in docker run command                                    |
| 502 Bad Gateway             | Backend service not running   | Ensure Keycloak (port 8080) and mock simulator (port 5000) are running                                         |
| 504 Gateway Timeout         | Slow backend response         | Increase nginx proxy timeouts in `nginx.conf`: `proxy_connect_timeout 60s;`                                    |
| Connection refused          | Wrong port or service stopped | Check service is listening: `netstat -tlnp \| grep :5000` or `lsof -i :5000`                                   |

### SSL & Certificate Issues

| Issue                           | Cause                              | Solution                                                                                  |
| ------------------------------- | ---------------------------------- | ----------------------------------------------------------------------------------------- |
| SSL certificate error           | Self-signed or untrusted cert      | Add `-k` to curl, accept in browser warning, or import cert to system trust store         |
| Certificate expired             | Self-signed cert passed expiration | Regenerate certificate with `openssl req -x509 -newkey rsa:4096 ...`                      |
| NET::ERR_CERT_AUTHORITY_INVALID | Browser doesn't trust cert         | For development: accept the risk, or configure Chrome with `--ignore-certificate-errors`  |
| `openssl` command not found     | OpenSSL not installed              | Install: `sudo apt-get install openssl` (Ubuntu/Debian) or `brew install openssl` (macOS) |

### CORS Issues

| Issue                                        | Cause                                     | Solution                                                          |
| -------------------------------------------- | ----------------------------------------- | ----------------------------------------------------------------- |
| CORS still occurring                         | Proxy not properly configured             | Verify `X-Forwarded-*` headers are present in `nginx.conf`        |
| CORS origin mismatch                         | Frontend and backend on different origins | Ensure both use same protocol/host/port through nginx proxy       |
| `Access-Control-Allow-Origin` header missing | Backend CORS not configured               | Check Spring Boot CORS configuration in backend, or proxy headers |

### Docker Issues

| Issue                     | Cause                      | Solution                                                                                   |
| ------------------------- | -------------------------- | ------------------------------------------------------------------------------------------ |
| Docker daemon not running | Docker service stopped     | Start Docker: `sudo systemctl start docker` (Linux) or open Docker Desktop (Windows/macOS) |
| Cannot connect to Docker  | Permission issue           | Add user to docker group: `sudo usermod -aG docker $USER` and logout/login                 |
| Image not found           | Image not pulled/built     | Pull or build image: `docker pull nginx:alpine` or `docker build -t name .`                |
| Port already in use       | Another service using port | Find process: `sudo lsof -i :443` and kill it, or use different port                       |
| Container exited          | Service crashed            | Check logs: `docker logs <container-name>` for error messages                              |

### Java & Maven Issues

| Issue                                   | Cause                            | Solution                                                                                |
| --------------------------------------- | -------------------------------- | --------------------------------------------------------------------------------------- |
| `java: command not found`               | Java not installed               | Install Java 21: Use `sdk install java 21.0.1-tem` (sdkman) or download from oracle.com |
| Wrong Java version                      | Multiple Java versions installed | Check: `java -version`, switch with `sdk use java 21.0.1-tem` (sdkman)                  |
| `mvn: command not found`                | Maven not in PATH                | Install Maven or add to PATH, verify with `mvn -version`                                |
| Build fails: `target/classes not found` | Stale build artifacts            | Run `mvn clean` before building                                                         |
| Compilation errors after code changes   | Stale compiler cache             | Clear target: `rm -rf target/` and rebuild                                              |
| Tests fail with encoding errors         | Character encoding issue         | Add to Maven: `<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>`      |

### Application Runtime Issues

| Issue                         | Cause                          | Solution                                                             |
| ----------------------------- | ------------------------------ | -------------------------------------------------------------------- |
| Application won't start       | Port already in use            | Change port in `application.yaml` or kill process using port 5000    |
| TypeScript compilation errors | Node.js not installed          | Maven frontend plugin handles this, or manually install Node 20.11.1 |
| Template not rendering        | Thymeleaf template missing     | Check file exists in `src/main/resources/views/` with correct name   |
| Static resources 404          | Resources not copied to target | Run `mvn clean compile` to rebuild and copy resources                |
| Bundle not loaded             | JavaScript bundle missing      | Run `npm run build` to generate bundles in `static/js/`              |

### Testing Issues

| Issue                         | Cause                       | Solution                                                                |
| ----------------------------- | --------------------------- | ----------------------------------------------------------------------- |
| Tests fail: JWT parsing error | Invalid test JWT token      | Use tokens generated with valid RSA key from `static/keys/rsa-jwk.json` |
| Tests timeout                 | Slow mock creation          | Increase timeout in test or optimize mocks with Mockito                 |
| `@SpringBootTest` fails       | Spring context not loading  | Check `application.yaml` and Spring configuration beans                 |
| Test reports not generated    | Surefire plugin issue       | Run: `mvn surefire-report:report` to generate HTML reports              |
| Test class not found          | Incorrect package structure | Tests must be in `src/test/java/de/arbeitsagentur/pushmfasim/...`       |

### Debugging Issues

| Issue                       | Cause                               | Solution                                                                                                                              |
| --------------------------- | ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| Breakpoint not hit          | Debug mode not enabled              | Start with: `mvn spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"` |
| Cannot attach debugger      | JDWP port not exposed               | Check debug server is listening on port 5005: `netstat -tlnp \| grep 5005`                                                            |
| Variable values not visible | Wrong stack frame selected          | Ensure you're on correct frame in VS Code debug panel                                                                                 |
| Stepping doesn't work       | Source not compiled with debug info | Rebuild with `-g` flag in compiler options                                                                                            |

### Frontend Issues

| Issue                             | Cause                 | Solution                                                                    |
| --------------------------------- | --------------------- | --------------------------------------------------------------------------- |
| Enroll/Confirm page showing blank | Bundle not loaded     | Check browser console for 404s, verify bundle exists in `target/static/js/` |
| TypeScript errors in console      | Source map missing    | Ensure `npm run build` generated `.map` files alongside bundles             |
| Styling not applied               | CSS bundle not loaded | Check `static/css/layout.css` exists and is referenced in template          |
| Form submission fails             | CSRF token missing    | Ensure hidden CSRF token in form with name `_csrf`                          |

### Performance & Optimization

| Issue              | Cause                      | Solution                                                                             |
| ------------------ | -------------------------- | ------------------------------------------------------------------------------------ |
| Slow page load     | Large bundles              | Run `npm run build` to optimize, check bundle size with `npm run build -- --analyze` |
| High memory usage  | Memory leak in application | Profile with VisualVM or JProfiler, check for unclosed resources                     |
| Slow API responses | N+1 query problem          | Review database queries, use proper caching and lazy loading                         |

### Development Workflow Issues

| Issue                        | Cause                  | Solution                                                             |
| ---------------------------- | ---------------------- | -------------------------------------------------------------------- |
| Changes not reflected        | Watch mode not running | Start watch: `npm run dev` in separate terminal                      |
| Formatting issues at commit  | Spotless not enforced  | Run `mvn spotless:apply` before committing                           |
| Linting errors prevent build | ESLint strict mode     | Fix with `npm run lint:fix` or disable specific rules in `.eslintrc` |
| Code not formatted correctly | Prettier not applied   | Run `npm run format` before committing                               |

### Environment & Configuration Issues

| Issue                                 | Cause                   | Solution                                                                                 |
| ------------------------------------- | ----------------------- | ---------------------------------------------------------------------------------------- |
| Application won't connect to Keycloak | Wrong Keycloak URL      | Check `KEYCLOAK_URL` environment variable and ensure Keycloak is reachable               |
| Client credentials not loaded         | Missing `.env` file     | Create `.env` file with `CLIENT_ID` and `CLIENT_SECRET` values                           |
| Realm configuration missing           | Demo realm not imported | Import `config/demo-realm.json` to Keycloak admin console                                |
| DPoP token validation fails           | Missing DPoP header     | Ensure frontend includes DPoP-Proof header in requests to `/realms/<realm>/push-mfa/...` |

## Configuration

### Application Settings

Edit `src/main/resources/application.yaml`:

```yaml
server:
  port: 5000
  address: 0.0.0.0
  servlet:
    context-path: /mock

app:
  env: 'dev'
```

### Demo Realm

The example realm JSON configuration is located at `config/demo-realm.json` and defines the realm `demo`.

Helper scripts and documentation should reference `/realms/demo/...` endpoints.

## Project Structure

```
src/main/resources/
├── static/
│   ├── js/          # Compiled JavaScript bundles
│   ├── ts/          # TypeScript source files
│   │   ├── pages/   # Page-specific logic (enroll, confirm, info)
│   │   └── util/    # Shared utilities (crypto, HTTP, tokens)
│   └── keys/        # JWK keys for crypto operations
├── views/           # Thymeleaf HTML templates
└── application.yaml # Spring Boot configuration
```

## Device-Facing Endpoints

Device endpoints are located under `/realms/<realm>/push-mfa/...` and expect **DPoP-bound tokens** (Demonstration of Proof-of-Possession).

Keep samples and tests aligned with the current realm name and URL structure.
Device Client Configuration (Client ID / Client Secret)

## Device Client Configuration (Client ID / Client Secret)

The simulator requires a configured Device Client (e.g. in Keycloak).
Typically the following values are needed:

Device Client ID – public, may be used in the frontend
Device Client Secret – confidential, must never be bundled or committed to the frontend

To support local development, Docker, and reverse-proxy setups safely, the configuration is split into:

Frontend configuration via thymeleaf
Backend configuration via Spring Boot (secrets via environment variables or config files)

## Firebase backend Mock

This mocks the firebase fcm googleapis enpoints for testing purpose.

### Preconditions 
- A implemented and configured Firebase FCM Provider in keycloak with ID "fcm"
- Enrollment done with Provider Type "fcm"

### Endpoints

*/fcm/token*
getting an access token 

*/fcm/messages:send*
sending a push message

*/fcm/credentials*
provide mock service account credentials
