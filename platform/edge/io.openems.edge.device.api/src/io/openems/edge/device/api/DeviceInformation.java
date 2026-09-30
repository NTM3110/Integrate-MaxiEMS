package io.openems.edge.device.api;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.StringReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

/** Common identification and version information of a physical device. */
@ProviderType
public interface DeviceInformation extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		MANUFACTURER(metadataDoc()), //
		MODEL(metadataDoc()), //
		SERIAL_NUMBER(metadataDoc()), //
		FIRMWARE_VERSION(metadataDoc()), //
		FIRMWARE_BUILD_DATE(metadataDoc()),
		HARDWARE_VERSION(metadataDoc()), //
		DEVICE_NAME(metadataDoc()), //
		DEVICE_UPTIME(Doc.of(OpenemsType.LONG) //
				.unit(Unit.CUMULATED_SECONDS) //
				.persistencePriority(PersistencePriority.MEDIUM));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}

		private static Doc metadataDoc() {
			return Doc.of(OpenemsType.STRING) //
					.persistencePriority(PersistencePriority.MEDIUM);
		}
	}

	public default StringReadChannel getManufacturerChannel() {
		return this.channel(ChannelId.MANUFACTURER);
	}

	public default Value<String> getManufacturer() {
		return this.getManufacturerChannel().value();
	}

	public default void _setManufacturer(String value) {
		this.getManufacturerChannel().setNextValue(value);
	}

	public default StringReadChannel getModelChannel() {
		return this.channel(ChannelId.MODEL);
	}

	public default Value<String> getModel() {
		return this.getModelChannel().value();
	}

	public default void _setModel(String value) {
		this.getModelChannel().setNextValue(value);
	}

	public default StringReadChannel getSerialNumberChannel() {
		return this.channel(ChannelId.SERIAL_NUMBER);
	}

	public default Value<String> getSerialNumber() {
		return this.getSerialNumberChannel().value();
	}

	public default void _setSerialNumber(String value) {
		this.getSerialNumberChannel().setNextValue(value);
	}

	public default StringReadChannel getFirmwareVersionChannel() {
		return this.channel(ChannelId.FIRMWARE_VERSION);
	}

	public default Value<String> getFirmwareVersion() {
		return this.getFirmwareVersionChannel().value();
	}

	public default void _setFirmwareVersion(String value) {
		this.getFirmwareVersionChannel().setNextValue(value);
	}

	public default StringReadChannel getFirmwareBuildDateChannel() {
		return this.channel(ChannelId.FIRMWARE_BUILD_DATE);
	}

	public default Value<String> getFirmwareBuildDate() {
		return this.getFirmwareBuildDateChannel().value();
	}

	public default void _setFirmwareBuildDate(String value) {
		this.getFirmwareBuildDateChannel().setNextValue(value);
	}

	public default StringReadChannel getHardwareVersionChannel() {
		return this.channel(ChannelId.HARDWARE_VERSION);
	}

	public default Value<String> getHardwareVersion() {
		return this.getHardwareVersionChannel().value();
	}

	public default void _setHardwareVersion(String value) {
		this.getHardwareVersionChannel().setNextValue(value);
	}

	public default StringReadChannel getDeviceNameChannel() {
		return this.channel(ChannelId.DEVICE_NAME);
	}

	public default Value<String> getDeviceName() {
		return this.getDeviceNameChannel().value();
	}

	public default void _setDeviceName(String value) {
		this.getDeviceNameChannel().setNextValue(value);
	}

	public default LongReadChannel getDeviceUptimeChannel() {
		return this.channel(ChannelId.DEVICE_UPTIME);
	}

	public default Value<Long> getDeviceUptime() {
		return this.getDeviceUptimeChannel().value();
	}

	public default void _setDeviceUptime(Long value) {
		this.getDeviceUptimeChannel().setNextValue(value);
	}
}
