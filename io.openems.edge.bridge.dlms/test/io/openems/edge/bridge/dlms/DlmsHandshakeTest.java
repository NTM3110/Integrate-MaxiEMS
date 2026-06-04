// package io.openems.edge.bridge.dlms;

// import org.junit.Test;
// import gurux.common.GXCommon;
// import gurux.common.ReceiveParameters;
// import gurux.dlms.GXByteBuffer;
// import gurux.dlms.GXDLMSClient;
// import gurux.dlms.GXReplyData;
// import gurux.dlms.enums.InterfaceType;
// import gurux.io.BaudRate;
// import gurux.io.Parity;
// import gurux.io.StopBits;
// import gurux.serial.GXSerial;

// /**
//  * Standalone DLMS handshake test — no GXDLMSReader.
//  * Applies the correct receive patterns from the working Gurux sample client:
//  *   - setAllData(true) for complete framed packets
//  *   - setCount(getFrameSize) for byte-count hint
//  *   - synchronized on getSynchronous()
//  *   - retry loop on timeout
//  *   - getData() loop until full frame parsed
//  */
// public class DlmsHandshakeTest {

//     private static final String PORT    = "/dev/moxa_uport_1150";
//     private static final int    CLIENT  = 16;     // -c 16
//     private static final int    SERVER  = 26322;  // -s 9938 -l 1  => 0x66D2
//     private static final int    WAIT_MS = 60000;  // 60s, same as GXDLMSReader default

//     // ── Helpers ──────────────────────────────────────────────────────────────

//     /** Send a request and receive a complete DLMS reply, with retries. */
//     private GXReplyData sendAndReceive(GXSerial serial, GXDLMSClient client,
//             byte[] request, String label) throws Exception {

//         GXByteBuffer rd    = new GXByteBuffer();
//         GXReplyData  reply = new GXReplyData();
//         GXReplyData  notify = new GXReplyData();

//         ReceiveParameters<byte[]> p = new ReceiveParameters<>(byte[].class);
//         p.setEop((byte) 0x7E);               // HDLC frame delimiter
//         p.setAllData(true);                  // wait for the COMPLETE framed packet
//         p.setCount(client.getFrameSize(rd)); // expected byte count
//         p.setWaitTime(WAIT_MS);

//         boolean succeeded = false;
//         int     retries   = 0;

//         synchronized (serial.getSynchronous()) {
//             while (!succeeded) {
//                 System.out.println("TX (" + label + "): " + GXCommon.bytesToHex(request));
//                 serial.send(request, null);

//                 succeeded = serial.receive(p);

//                 if (!succeeded) {
//                     if (++retries == 3) {
//                         throw new RuntimeException(
//                                 "Timeout: no reply for " + label + " after 3 attempts.");
//                     }
//                     System.out.println("  No reply, retry " + retries + "/3...");
//                     p.setReply(null);
//                     p.setCount(client.getFrameSize(rd));
//                 }
//             }

//             // Accumulate chunks until a full DLMS frame is parsed
//             rd = new GXByteBuffer(p.getReply());
//             System.out.println("RX (" + label + "): " + GXCommon.bytesToHex(p.getReply()));

//             while (!client.getData(rd, reply, notify)) {
//                 p.setReply(null);
//                 p.setCount(client.getFrameSize(rd));
//                 if (!serial.receive(p)) {
//                     throw new RuntimeException(
//                             "Timeout waiting for continuation of " + label + " frame.");
//                 }
//                 System.out.println("  RX cont: " + GXCommon.bytesToHex(p.getReply()));
//                 rd.set(p.getReply());
//             }
//         }

//         return reply;
//     }

//     // ── Test ─────────────────────────────────────────────────────────────────

//     @Test(timeout = 90000)
//     public void handshakeTest() throws Exception {

//         // 1. Configure serial port
//         GXSerial serial = new GXSerial();
//         serial.setPortName(PORT);
//         serial.setBaudRate(BaudRate.BAUD_RATE_9600);
//         serial.setDataBits(8);
//         serial.setParity(Parity.NONE);
//         serial.setStopBits(StopBits.ONE);

//         // 2. Configure DLMS client  (-r sn -c 16 -s 9938 -l 1)
//         GXDLMSClient client = new GXDLMSClient();
//         client.setUseLogicalNameReferencing(false);
//         client.setInterfaceType(InterfaceType.HDLC);
//         client.setClientAddress(CLIENT);
//         client.setServerAddress(SERVER);

//         System.out.printf("Port %s | Client %d | Server %d (0x%X)%n",
//                 PORT, CLIENT, SERVER, SERVER);

//         // 3. Open port THEN set DTR/RTS (order matters!)
//         serial.open();
//         serial.setDtrEnable(true);
//         serial.setRtsEnable(true);
//         Thread.sleep(1000); // let port stabilise

//         try {
//             // ─── HDLC layer: SNRM → UA ───────────────────────────────────
//             GXReplyData ua = sendAndReceive(serial, client, client.snrmRequest(), "SNRM");

//             if (ua.getError() != 0) {
//                 throw new RuntimeException("UA error: " + ua.getError());
//             }
//             client.parseUAResponse(ua.getData());
//             System.out.println("✓ UA received – HDLC layer connected!");

//             // ─── Application layer: AARQ → AARE ─────────────────────────
//             byte[][] aarqFrames = client.aarqRequest();
//             GXReplyData aare = null;

//             for (byte[] frame : aarqFrames) {
//                 aare = sendAndReceive(serial, client, frame, "AARQ");
//             }

//             if (aare == null || aare.getError() != 0) {
//                 throw new RuntimeException("AARE error: " + (aare != null ? aare.getError() : "null"));
//             }
//             client.parseAareResponse(aare.getData());
//             System.out.println("✓ AARE received – Application layer connected!");
//             System.out.println("  MaxPDU             : " + client.getMaxReceivePDUSize());
//             System.out.println("  Negotiated conform.: " + client.getNegotiatedConformance());

//         } finally {
//             // Polite HDLC disconnect
//             if (serial.isOpen()) {
//                 try {
//                     sendAndReceive(serial, client, client.disconnectRequest(), "DISC");
//                 } catch (Exception ignore) { }
//                 serial.close();
//                 System.out.println("Port closed.");
//             }
//         }
//     }
// }
