package io.openems.edge.bms.service.site.implement;

import io.openems.edge.bms.service.site.implement.persistence.SiteSettingRepository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.Test;

public class SiteSettingServiceImplTest {

	@Test
	public void returnsDefaultWhenSettingIsMissing() throws Exception {
		var service = new SiteSettingServiceImpl(new MemoryRepository());

		assertEquals(SiteSettingServiceImpl.DEFAULT_SITE_NAME, service.getSiteName());
	}

	@Test
	public void trimsAndPersistsUpdater() throws Exception {
		var repository = new MemoryRepository();
		var service = new SiteSettingServiceImpl(repository);
		var updater = UUID.randomUUID();

		assertEquals("Battery Room", service.setSiteName("  Battery Room  ", updater));
		assertEquals("Battery Room", service.getSiteName());
		assertEquals(updater, repository.updatedBy);
	}

	@Test
	public void rejectsBlankAndOverlongNames() {
		var service = new SiteSettingServiceImpl(new MemoryRepository());
		var updater = UUID.randomUUID();

		assertThrows(IllegalArgumentException.class, () -> service.setSiteName("  ", updater));
		assertThrows(IllegalArgumentException.class,
				() -> service.setSiteName("x".repeat(SiteSettingServiceImpl.MAX_SITE_NAME_LENGTH + 1), updater));
	}

	private static final class MemoryRepository implements SiteSettingRepository {
		private final Map<String, String> values = new HashMap<>();
		private UUID updatedBy;

		@Override
		public Optional<String> find(String key) {
			return Optional.ofNullable(this.values.get(key));
		}

		@Override
		public void save(String key, String value, UUID updatedBy) {
			this.values.put(key, value);
			this.updatedBy = updatedBy;
		}
	}
}
