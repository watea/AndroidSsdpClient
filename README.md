# AndroidSsdpClient

**AndroidSsdpClient** is a lightweight and easy-to-use library for implementing SSDP (Simple Service Discovery Protocol) client functionality in Android. It is ideal for applications requiring UPnP device discovery and management in local networks.

## Installation

### Using GitHub Packages

To include this library in your project via GitHub Packages, follow these steps. Replace `TAG` with the desired version tag of the release.

1. Add the GitHub Packages repository to your project’s `build.gradle` file. Use your GitHub token from an environment variable (e.g., `GITHUB_TOKEN`).

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/YOUR_USERNAME/YOUR_REPOSITORY")
        credentials {
            username = "YOUR_USERNAME"
            password = System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
}
```

2. Add the dependency.

```groovy
dependencies {
    implementation 'com.watea.androidssdpclient:TAG'
}
```

> **Note:** Replace `YOUR_USERNAME` and `YOUR_REPOSITORY` with your GitHub username and the repository name. Ensure the `GITHUB_TOKEN` environment variable is set with `read:packages` permissions.

### Using JitPack

Alternatively, you can include this library using [JitPack](https://jitpack.io/).

1. Add the JitPack repository to your top-level `settings.gradle` file:

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

2. Add the dependency in your module’s `build.gradle` file:

```groovy
dependencies {
    implementation 'com.github.watea:androidssdpclient:TAG'
}
```

## Usage

### Configuring and Starting the Client

The library provides the `SsdpClient` class for discovering and managing UPnP devices. To use it:

### Example: Setting Up the Client

```java
import com.watea.androidssdpclient.SsdpClient;

public class MySsdpClient {
    private SsdpClient client;

    public void startClient() {
        SsdpClient.Listener listener = new SsdpClient.Listener() {
            @Override
            public void onServiceDiscovered(@NonNull SsdpService service) {
                System.out.println("Discovered: " + service);
            }

            @Override
            public void onServiceAnnouncement(@NonNull SsdpService service) {
                System.out.println("Announcement: " + service);
            }

            @Override
            public void onFatalError() {
                System.err.println("Fatal error occurred.");
            }

            @Override
            public void onStop() {
                System.out.println("SSDP Client stopped.");
            }
        };

        client = new SsdpClient(listener);
        client.start();
    }

    public void stopClient() {
        if (client != null) {
            client.stop();
        }
    }
}
```

### Complete Example

A more complete example of using **AndroidSsdpClient** in a real-world application can be found in the project **[RadioUpnp](https://github.com/watea/RadioUpnp)**. This project demonstrates how to integrate and extend the library.

### Key Features

- **Multicast and Unicast Communication:** Supports SSDP multicast and unicast responses.
- **Device Discovery:** Searches for UPnP devices using the "M-SEARCH" method.
- **Service Announcement Handling:** Processes NOTIFY messages to detect changes in device availability.
- **Thread-Safe Service Caching:** Manages discovered services in a thread-safe manner.
- **Event Listener Integration:** Notifies the application about discovery events, announcements, or errors.

### Event Handling with Listeners

Implement the `SsdpClient.Listener` interface to handle SSDP events. For example:

- `void onServiceDiscovered(@NonNull SsdpService service)`
    - Called when a new service is discovered.
- `void onServiceAnnouncement(@NonNull SsdpService service)`
    - Called when a service sends a notification (e.g., alive or byebye).
- `void onFatalError()`
    - Called when a fatal error occurs.
- `void onStop()`
    - Called when the client stops.

### Advanced Configuration

- `search()`: Sends a single M-SEARCH request to discover devices.
- `stop()`: Stops the client and releases resources.
- `isStarted()`: Checks if the client is running.

### Dependencies

The `SsdpClient` class relies on the following classes:

- **`SsdpResponse`:** Represents an SSDP response or notification.
- **`SsdpService`:** Encapsulates details about a discovered or announced service.

## Discussion

The used primitives and .gradle integration has been foreseen to work seamlessly in Android environment. But you may use the library in any other projects.

## License

MIT