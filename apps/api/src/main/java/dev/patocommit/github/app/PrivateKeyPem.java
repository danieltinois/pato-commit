package dev.patocommit.github.app;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Reads the RSA private key GitHub issues for an App.
 *
 * <p>GitHub hands out PKCS#1 ({@code BEGIN RSA PRIVATE KEY}), but the JDK's
 * {@link KeyFactory} only reads PKCS#8. The two differ by a fixed envelope: PKCS#8
 * is the PKCS#1 bytes wrapped in an {@code AlgorithmIdentifier} that says "this is
 * RSA". Building that envelope here is a few lines of DER, and avoids pulling in
 * BouncyCastle for a single conversion.
 */
final class PrivateKeyPem {

	private static final String PKCS1_HEADER = "-----BEGIN RSA PRIVATE KEY-----";

	private static final String PKCS1_FOOTER = "-----END RSA PRIVATE KEY-----";

	private static final String PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----";

	private static final String PKCS8_FOOTER = "-----END PRIVATE KEY-----";

	/**
	 * DER for {@code INTEGER 0} (the PKCS#8 version) followed by
	 * {@code SEQUENCE { OID 1.2.840.113549.1.1.1 (rsaEncryption), NULL }}.
	 */
	private static final byte[] VERSION_AND_RSA_ALGORITHM = {
			0x02, 0x01, 0x00,
			0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00 };

	private static final byte DER_SEQUENCE = 0x30;

	private static final byte DER_OCTET_STRING = 0x04;

	private PrivateKeyPem() {
	}

	/**
	 * Parses the PEM text. Throws {@link IllegalArgumentException} without quoting
	 * the input: the input is a private key, and the message may end up in a log.
	 */
	static RSAPrivateKey parse(String pem) {
		String text = pem.replace("\\n", "\n").strip();
		byte[] pkcs8;
		if (text.startsWith(PKCS1_HEADER)) {
			pkcs8 = wrapPkcs1(decodeBody(text, PKCS1_HEADER, PKCS1_FOOTER));
		}
		else if (text.startsWith(PKCS8_HEADER)) {
			pkcs8 = decodeBody(text, PKCS8_HEADER, PKCS8_FOOTER);
		}
		else {
			throw new IllegalArgumentException("private key is not a PEM-encoded RSA key");
		}
		try {
			return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
		}
		catch (GeneralSecurityException | ClassCastException e) {
			// The cause is dropped on purpose: some providers quote key material in it.
			throw new IllegalArgumentException("private key could not be read as an RSA key");
		}
	}

	private static byte[] decodeBody(String text, String header, String footer) {
		int end = text.indexOf(footer);
		if (end < 0) {
			throw new IllegalArgumentException("private key PEM has no END line");
		}
		String base64 = text.substring(header.length(), end).replaceAll("\\s", "");
		try {
			return Base64.getDecoder().decode(base64);
		}
		catch (IllegalArgumentException malformed) {
			throw new IllegalArgumentException("private key PEM body is not valid base64");
		}
	}

	/** {@code SEQUENCE { version, algorithm, OCTET STRING { pkcs1 } }}. */
	static byte[] wrapPkcs1(byte[] pkcs1) {
		byte[] octetString = tagged(DER_OCTET_STRING, pkcs1);
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.writeBytes(VERSION_AND_RSA_ALGORITHM);
		body.writeBytes(octetString);
		return tagged(DER_SEQUENCE, body.toByteArray());
	}

	private static byte[] tagged(byte tag, byte[] content) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(tag);
		writeLength(out, content.length);
		out.writeBytes(content);
		return out.toByteArray();
	}

	/**
	 * DER length: one byte below 128, otherwise {@code 0x80 | n} followed by the
	 * length in {@code n} big-endian bytes.
	 */
	private static void writeLength(ByteArrayOutputStream out, int length) {
		if (length < 0x80) {
			out.write(length);
			return;
		}
		int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
		out.write(0x80 | bytes);
		for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
			out.write((length >>> shift) & 0xff);
		}
	}

}
