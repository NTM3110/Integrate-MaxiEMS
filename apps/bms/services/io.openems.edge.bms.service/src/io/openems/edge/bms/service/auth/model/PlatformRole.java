package io.openems.edge.bms.service.auth.model;

/** Roles shared by BMS now and RTS/BESS modules later. */
public enum PlatformRole {
	PLATFORM_ADMIN,
	BMS_OPERATOR,
	BMS_VIEWER,
	MAINTENANCE,
	RTS_OPERATOR,
	BESS_OPERATOR,
	BESS_VIEWER;
}
