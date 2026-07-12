package klepaas.backend.auth.oauth;

import klepaas.backend.auth.config.GitHubAppConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubAppJwtProviderTest {

    private static RSAPrivateCrtKey privateKey;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = (RSAPrivateCrtKey) keyPair.getPrivate();
    }

    @Test
    @DisplayName("PKCS#8 PEM private key로 GitHub App JWT를 생성한다")
    void generateAppJwt_supportsPkcs8Pem() {
        GitHubAppJwtProvider provider = provider(pkcs8Pem());

        String jwt = provider.generateAppJwt();

        assertThat(jwt.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("literal newline escape가 포함된 PEM private key로 GitHub App JWT를 생성한다")
    void generateAppJwt_supportsEscapedNewlinePem() {
        GitHubAppJwtProvider provider = provider(pkcs8Pem().replace("\n", "\\n"));

        String jwt = provider.generateAppJwt();

        assertThat(jwt.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("PKCS#1 RSA PEM private key로 GitHub App JWT를 생성한다")
    void generateAppJwt_supportsPkcs1RsaPem() {
        GitHubAppJwtProvider provider = provider(pkcs1RsaPem());

        String jwt = provider.generateAppJwt();

        assertThat(jwt.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("PEM marker가 없는 PKCS#1 RSA private key 본문으로 GitHub App JWT를 생성한다")
    void generateAppJwt_supportsPkcs1RsaBodyOnly() {
        GitHubAppJwtProvider provider = provider(base64Body(pkcs1RsaDer()));

        String jwt = provider.generateAppJwt();

        assertThat(jwt.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("잘못된 private key는 명확한 파싱 오류를 던진다")
    void generateAppJwt_throwsWhenPrivateKeyIsInvalid() {
        GitHubAppJwtProvider provider = provider("not-a-private-key");

        assertThatThrownBy(provider::generateAppJwt)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub App private key 파싱 실패");
    }

    private GitHubAppJwtProvider provider(String privateKeyPem) {
        GitHubAppConfig config = new GitHubAppConfig();
        config.setAppId(12345L);
        config.setPrivateKey(privateKeyPem);
        return new GitHubAppJwtProvider(config);
    }

    private String pkcs8Pem() {
        return pem("PRIVATE KEY", privateKey.getEncoded());
    }

    private String pkcs1RsaPem() {
        return pem("RSA PRIVATE KEY", pkcs1RsaDer());
    }

    private byte[] pkcs1RsaDer() {
        return derSequence(concat(
                derInteger(BigInteger.ZERO),
                derInteger(privateKey.getModulus()),
                derInteger(privateKey.getPublicExponent()),
                derInteger(privateKey.getPrivateExponent()),
                derInteger(privateKey.getPrimeP()),
                derInteger(privateKey.getPrimeQ()),
                derInteger(privateKey.getPrimeExponentP()),
                derInteger(privateKey.getPrimeExponentQ()),
                derInteger(privateKey.getCrtCoefficient())
        ));
    }

    private String pem(String marker, byte[] der) {
        return "-----BEGIN " + marker + "-----\n"
                + base64Body(der)
                + "\n-----END " + marker + "-----";
    }

    private String base64Body(byte[] der) {
        return Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
    }

    private byte[] derInteger(BigInteger value) {
        return concat(new byte[]{0x02}, derLength(value.toByteArray().length), value.toByteArray());
    }

    private byte[] derSequence(byte[] value) {
        return concat(new byte[]{0x30}, derLength(value.length), value);
    }

    private byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }

        List<Byte> bytes = new ArrayList<>();
        int remaining = length;
        while (remaining > 0) {
            bytes.add(0, (byte) (remaining & 0xff));
            remaining >>= 8;
        }

        byte[] result = new byte[bytes.size() + 1];
        result[0] = (byte) (0x80 | bytes.size());
        for (int i = 0; i < bytes.size(); i++) {
            result[i + 1] = bytes.get(i);
        }
        return result;
    }

    private byte[] concat(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] array : arrays) {
            totalLength += array.length;
        }

        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
