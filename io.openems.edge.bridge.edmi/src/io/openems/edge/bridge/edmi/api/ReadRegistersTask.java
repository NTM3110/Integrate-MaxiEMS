package io.openems.edge.bridge.edmi.api;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import io.openems.edge.bridge.edmi.EdmiBridge;
import io.openems.edge.common.taskmanager.Priority;

/**
 * A concrete Task for reading real-time registers from an EDMI meter.
 */
public class ReadRegistersTask extends AbstractEdmiTask {

	private final String meterId;

	public ReadRegistersTask(Priority priority, String meterId, EdmiElement... elements) {
		super(priority);
        this.meterId = meterId;
	}

	@Override
	public void execute() throws Exception {

//		// Schedule next run in 30 seconds
//		List<Object> objects = this.readBillingValuesFromHardware(bridge);
//		String date = objects.get(2).toString();
//		String time = objects.get(3).toString();
//		LocalDateTime ldt = LocalDateTime.parse(date + "T" + time);
//		Point point = Point.measurement("edmi_billing_values") //
//				.addTag("meter_id", this.meterId) //
//				.time(ldt.toInstant(ZoneOffset.ofHours(7)), WritePrecision.MS) //
//				.addField("meter_serial_number", objects.get(0).toString()) //
//				.addField("total_energy_tot_imp_wh", (Number) row.get(2)) //
//				.addField("total_energy_tot_exp_wh", (Number) row.get(3));
//		this.setNextRunTime(System.currentTimeMillis() + 30 * 1000);
	}

//	public List<Object> readBillingValuesFromHardware(EdmiBridge bridge) throws Exception {
////		return Collections.singletonList(bridge.sendRequest("billing"));
//	}
}
