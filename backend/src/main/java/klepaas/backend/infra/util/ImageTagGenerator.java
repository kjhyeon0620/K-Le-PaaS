package klepaas.backend.infra.util;

public final class ImageTagGenerator {

    private static final int SHORT_SHA_LENGTH = 7;

    private ImageTagGenerator() {
    }

    public static String toShortSha(String commitHash) {
        if (commitHash == null || commitHash.isBlank()) {
            throw new IllegalArgumentException("commitHash must not be blank");
        }
        return commitHash.substring(0, Math.min(SHORT_SHA_LENGTH, commitHash.length()));
    }

    public static String buildImageUri(String registryEndpoint, String imageName, String commitHash) {
        if (registryEndpoint == null || registryEndpoint.isBlank()) {
            throw new IllegalArgumentException("registryEndpoint must not be blank");
        }
        if (imageName == null || imageName.isBlank()) {
            throw new IllegalArgumentException("imageName must not be blank");
        }
        return registryEndpoint + "/" + imageName + ":" + toShortSha(commitHash);
    }
}
