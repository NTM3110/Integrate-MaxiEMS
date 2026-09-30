package io.openems.edge.network.manager.os;

public record NetworkVerification(boolean profileVerified, boolean runtimeVerified, boolean linkAvailable) {
}
