package io.openems.edge.meter.elster.a1700.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.openems.edge.meter.elster.a1700.ElsterA1700MeterImpl;

public class A1700ProtocolTest {
	@Test
	@SuppressWarnings("unchecked")
	public void profileDateFilterUsesDecodedMeterTimestamps() {
		var intervals = new ArrayList<Object>(List.of(//
				A1700Data.map("start", "2026-09-23T23:30", "end", "2026-09-24T00:00"), //
				A1700Data.map("start", "2026-09-24T00:00", "end", "2026-09-24T00:30"), //
				A1700Data.map("start", "2026-09-25T00:00", "end", "2026-09-25T00:30")));
		var decoded = A1700Data.map("data", A1700Data.map("intervals", intervals));

		A1700Decoder.filterProfileByDate(decoded, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 24));

		var data = (Map<String, Object>) decoded.get("data");
		assertEquals(1, ((List<Object>) data.get("intervals")).size());
		var query = (Map<String, Object>) data.get("profileQuery");
		assertEquals("decoded_meter_timestamp", query.get("filterMethod"));
		assertEquals(3, query.get("originalIntervalCount"));
		assertEquals(1, query.get("returnedIntervalCount"));
	}

	@Test
	public void profileDateSelectorUsesInclusiveLookback() {
		var today = LocalDate.of(2026, 9, 28);
		assertEquals("0100", ElsterA1700MeterImpl.profileSelectorForDates(today, today, today));
		assertEquals("0200", ElsterA1700MeterImpl.profileSelectorForDates(today.minusDays(1), today.minusDays(1), today));
		assertEquals("0A00", ElsterA1700MeterImpl.profileSelectorForDates(LocalDate.of(2026, 9, 19),
				LocalDate.of(2026, 9, 19), today));
		assertEquals("0A00", ElsterA1700MeterImpl.profileSelectorForDates(LocalDate.of(2026, 9, 19),
				LocalDate.of(2026, 9, 23), today));
		assertInvalidProfileDates(today.plusDays(1), today.plusDays(1), today);
		assertInvalidProfileDates(today.minusDays(255), today, today);
		assertInvalidProfileDates(today, today.minusDays(1), today);
	}

	private static void assertInvalidProfileDates(LocalDate fromDate, LocalDate toDate, LocalDate today) {
		try {
			ElsterA1700MeterImpl.profileSelectorForDates(fromDate, toDate, today);
			fail("Expected invalid date range");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	@Test
	public void challengeResponseMatchesVerifiedVector() {
		var key = A1700Protocol.unhex("0102030405060708");
		var challenge = A1700Protocol.unhex("1020304050607080");
		assertEquals("99BBEE3287ED64EC", A1700Protocol.hex(A1700Protocol.passwordResponse(key, challenge)));
	}

	@Test
	public void frameBccIsValidated() throws Exception {
		var frame = A1700Protocol.frame("R1", "798001(10)");
		A1700Protocol.checkFrame(frame);
		frame[frame.length - 1] ^= 1;
		try {
			A1700Protocol.checkFrame(frame);
			fail("Expected an IOException");
		} catch (IOException expected) {
			// expected
		}
	}

	@Test
	public void passwordsRequireEightAsciiCharacters() {
		ElsterA1700MeterImpl.validatePassword("Password 3", "FEDC0003");
		assertInvalidPassword("short");
		assertInvalidPassword("1234567é");
	}

	private static void assertInvalidPassword(String password) {
		try {
			ElsterA1700MeterImpl.validatePassword("Password 3", password);
			fail("Expected invalid password: " + password);
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}
}
