package io.openems.edge.bridge.dlms.api;

import java.util.Date;

public class DlmsBatchTarget {

	private final String componentId;
	private final String outstation;
	private final int serverAddress;
	private final String profileObis;
	private final Date start;
	private final Date end;

	public DlmsBatchTarget(String componentId, String outstation, int serverAddress) {
		this(componentId, outstation, serverAddress, null, null, null);
	}

	public DlmsBatchTarget(String componentId, String outstation, int serverAddress, String profileObis, Date start,
			Date end) {
		this.componentId = componentId;
		this.outstation = outstation;
		this.serverAddress = serverAddress;
		this.profileObis = profileObis;
		this.start = start;
		this.end = end;
	}

	public String componentId() {
		return this.componentId;
	}

	public String outstation() {
		return this.outstation;
	}

	public int serverAddress() {
		return this.serverAddress;
	}

	public String profileObis() {
		return this.profileObis;
	}

	public Date start() {
		return this.start;
	}

	public Date end() {
		return this.end;
	}
}
