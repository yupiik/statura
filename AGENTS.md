# AGENTS.md

## Project Overview

This is a Maven project for statura, a system that manages Kubernetes applications using Bundlebee.

## Key Commands

- `mvn clean install` - Build and install the project
- `mvn test` - Run tests
- `mvn compile` - Compile the project
- `mvn package` - Package the project
- `mvn verify` - Run verification (build + test + package)

## Build Structure

- `src/main/bundlebee/` - Main application code
- `src/main/bundlebee/kubernetes/` - Kubernetes manifests and configurations
- `src/main/bundlebee/manifests/` - Bundlebee manifests (statura.json, namespaces.json)
- `src/main/bundlebee/placeholders/` - Placeholder configurations (dev.checks.json)

## Testing

- `mvn test` - Run all tests
- Test classes are in `src/test/java/`
- Tests are grouped by functionality: check tests, Otel tests, etc.

## Deployment

- Kubernetes manifests are in `src/main/bundlebee/kubernetes/`
- Kubernetes application configurations in `src/main/bundlebee/kubernetes/applications/statura/`
- Namespace templates in `src/main/bundlebee/kubernetes/_templates/namespace/`

## Environment

- Uses Java 17+
- Maven-based build system
- Kubernetes cluster deployment targets

## Special Notes

- The project uses Bundlebee for application management
- Kubernetes manifests are defined in JSON format
- Application-specific configurations are in `src/main/bundlebee/kubernetes/applications/statura/`
- Manifests are defined in `src/main/bundlebee/manifests/`
- Placeholder configurations are used for development checks