package io.openems.edge.bridge.dlms;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import gurux.dlms.GXByteBuffer;
import gurux.dlms.GXCryptoKeyParameter;
import gurux.dlms.GXDLMSTranslator;
import gurux.dlms.IGXCryptoNotifier;
import gurux.dlms.IGXCustomObjectNotifier;
import gurux.dlms.asn.GXPkcs8;
import gurux.dlms.asn.GXx509Certificate;
import gurux.dlms.objects.GXDLMSObject;
import gurux.dlms.objects.enums.CertificateType;
import gurux.dlms.objects.enums.SecuritySuite;
import gurux.dlms.secure.GXDLMSSecureClient;

public class GXDLMSSecureClient2 extends GXDLMSSecureClient implements IGXCryptoNotifier, IGXCustomObjectNotifier {

	public GXDLMSSecureClient2(final boolean useLogicalNameReferencing) {
		super(useLogicalNameReferencing);
	}

	private static Path getPath(final SecuritySuite securitySuite, final CertificateType type, final String path,
			final byte[] systemTitle) {
		String pre;
		Path tmp;
		if (securitySuite == SecuritySuite.SUITE_2) {
			tmp = Paths.get(path, "384");
		} else {
			tmp = Paths.get(path);
		}
		if (systemTitle == null) {
			return tmp;
		}
		switch (type) {
		case DIGITAL_SIGNATURE:
			pre = "D";
			break;
		case KEY_AGREEMENT:
			pre = "A";
			break;
		default:
			throw new RuntimeException("Invalid type.");
		}
		return Paths.get(tmp.toString(), pre + GXDLMSTranslator.toHex(systemTitle, false) + ".pem");
	}

	@Override
	public void onPdu(Object sender, byte[] data) {
		// Log PDU if needed
	}

	@Override
	public void onKey(Object sender, GXCryptoKeyParameter args) {
		try {
			if (args.getEncrypt()) {
				// Find private key.
				Path path = getPath(args.getSecuritySuite(), args.getCertificateType(), "Keys", args.getSystemTitle());
				args.setPrivateKey(GXPkcs8.load(path).getPrivateKey());
			} else {
				// Find public key.
				Path path = getPath(args.getSecuritySuite(), args.getCertificateType(), "Certificates",
						args.getSystemTitle());
				args.setPublicKey(GXx509Certificate.load(path).getPublicKey());
			}
		} catch (IOException e) {
			// Silent error if keys are missing
		}
	}

	@Override
	public void onCrypto(final Object sender, final GXCryptoKeyParameter args) {
		// Hardware Security Module logic (omitted)
	}

	@Override
	public GXDLMSObject onObjectCreate(int type, int version) {
		return null;
	}
}
