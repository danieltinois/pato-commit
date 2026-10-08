package dev.patocommit.github.app;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The key pair is generated per run rather than checked in: a committed private
 * key, even a test one, trains secret scanners and people to ignore their alarms.
 */
class GitHubAppJwtSignerTests {

	private static final long APP_ID = 123456L;

	private static final URI API_URL = URI.create("https://api.github.com");

	private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	/**
	 * Length of the PKCS#8 envelope around a 2048-bit RSA key: SEQUENCE header (4),
	 * version (3), AlgorithmIdentifier (15), OCTET STRING header (4).
	 */
	private static final int PKCS8_ENVELOPE_2048 = 26;

	private static KeyPair keyPair;

	@BeforeAll
	static void generateKeyPair() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		keyPair = generator.generateKeyPair();
	}

	private static byte[] pkcs8() {
		return keyPair.getPrivate().getEncoded();
	}

	/** The same key as GitHub would download it: PKCS#1, inside PKCS#8. */
	private static byte[] pkcs1() {
		byte[] pkcs8 = pkcs8();
		return Arrays.copyOfRange(pkcs8, PKCS8_ENVELOPE_2048, pkcs8.length);
	}

	private static String pem(String type, byte[] der) {
		String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
		return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
	}

	private static GitHubAppJwtSigner signerWith(String privateKey) {
		return new GitHubAppJwtSigner(new GitHubAppProperties(APP_ID, privateKey, API_URL), CLOCK);
	}

	@Test
	void wrapsPkcs1IntoExactlyTheBytesTheJdkProducesForPkcs8() {
		// The strongest check on the DER envelope: rebuilding it from the inner key
		// yields the JDK's own encoding, byte for byte.
		assertThat(PrivateKeyPem.wrapPkcs1(pkcs1())).isEqualTo(pkcs8());
	}

	@Test
	void signsATokenGitHubCanVerifyWithThePublicKey() throws Exception {
		String jwt = signerWith(pem("RSA PRIVATE KEY", pkcs1())).sign();

		String[] parts = jwt.split("\\.");
		assertThat(parts).hasSize(3);

		Signature verifier = Signature.getInstance("SHA256withRSA");
		verifier.initVerify(keyPair.getPublic());
		verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
		assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
	}

	@Test
	void carriesTheClaimsGitHubRequires() {
		String jwt = signerWith(pem("RSA PRIVATE KEY", pkcs1())).sign();
		String[] parts = jwt.split("\\.");

		assertThat(decode(parts[0])).isEqualTo("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
		// iat backdated by the skew allowance; exp inside GitHub's ten-minute ceiling.
		assertThat(decode(parts[1])).isEqualTo("{\"iat\":%d,\"exp\":%d,\"iss\":\"%d\"}".formatted(
				NOW.getEpochSecond() - 60, NOW.getEpochSecond() + 9 * 60, APP_ID));
	}

	@Test
	void readsPkcs8AsWell() {
		assertThat(signerWith(pem("PRIVATE KEY", pkcs8())).sign()).isNotBlank();
	}

	@Test
	void acceptsEscapedNewlinesFromAnEnvironmentVariable() {
		String singleLine = pem("RSA PRIVATE KEY", pkcs1()).replace("\n", "\\n");
		assertThat(signerWith(singleLine).sign()).isNotBlank();
	}

	@Test
	void failsAtStartupOnAKeyThatIsNotAKey() {
		assertThatIllegalArgumentException().isThrownBy(() -> signerWith("not a key"));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> signerWith("-----BEGIN RSA PRIVATE KEY-----\n%%%%\n-----END RSA PRIVATE KEY-----"));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> signerWith("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"));
	}

	@Test
	void neverQuotesTheKeyInAnError() {
		String broken = "-----BEGIN RSA PRIVATE KEY-----\nc2VjcmV0LWtleS1tYXRlcmlhbA==\n-----END RSA PRIVATE KEY-----";
		assertThatIllegalArgumentException().isThrownBy(() -> signerWith(broken))
				.withMessageNotContaining("c2VjcmV0")
				.withNoCause();
	}

	@Test
	void bootsUnconfiguredButRefusesToSign() {
		GitHubAppJwtSigner signer = new GitHubAppJwtSigner(new GitHubAppProperties(null, "", API_URL), CLOCK);

		assertThat(signer.isConfigured()).isFalse();
		assertThatIllegalStateException().isThrownBy(signer::sign);
	}

	private static String decode(String base64url) {
		return new String(Base64.getUrlDecoder().decode(base64url), StandardCharsets.UTF_8);
	}

}
