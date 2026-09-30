package io.openems.edge.common.user;

import static org.junit.Assert.assertEquals;

import java.util.Base64;

import org.junit.Test;

public class ManagedUserCompatibilityTest {

	@Test
	public void testDotNetCompatibilityVector() {
		var salt = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODw==");
		var actual = ManagedUser.hashPassword("correct horse battery staple", salt,
				ManagedUser.ITERATIONS, ManagedUser.KEY_LENGTH);

		assertEquals("KJO73UbHuTKFTeEDNHHpnnZD3lF/reqnckEDr6HpgIE=", Base64.getEncoder().encodeToString(actual));
	}
}
