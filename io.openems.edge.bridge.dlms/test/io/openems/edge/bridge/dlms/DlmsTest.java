package io.openems.edge.bridge.dlms;

import org.junit.Test;
import gurux.dlms.GXDLMSClient;
import gurux.dlms.objects.GXDLMSObject;
import gurux.serial.GXSerial;
import gurux.common.GXCommon;
import io.openems.edge.bridge.dlms.GXDLMSReader;
import io.openems.edge.bridge.dlms.GXDLMSSecureClient2;
import gurux.dlms.objects.GXDLMSObjectCollection;
import gurux.dlms.objects.GXXmlWriterSettings;
import gurux.dlms.enums.ObjectType;
import java.io.File;

/**
 * This test verifies the DLMS communication using ported Gurux example classes.
 */
public class DlmsTest {

    @Test
    public void testImports() {
        // Simple test to ensure basic Gurux integration is working
        GXDLMSClient client = new GXDLMSClient();
        System.out.println("Gurux DLMS Client initialized successfully!");
        System.out.println("Default Interface Type: " + client.getInterfaceType());
    }

    /**
     * This test performs a full DLMS handshake and reads the association view.
     * It mirrors the logic of the Gurux sample client.
     */
    @Test(timeout = 60000) // Increased timeout for full handshake
    public void manualCommunicationTest() throws Exception {
        System.out.println("Starting Full DLMS Communication Test...");

        Settings settings = new Settings();
        
        // Match the working Maven example parameters:
        // -S /dev/moxa_uport_1150:9600:8None1 -c 16 -s 9938 -l 1 -r sn -t Verbose
        String[] args = {
            "-S", "COM9:9600:8None1",
            "-c", "16",
            "-s", "9938",
            "-l", "1",
            "-r", "sn",
            "-o", "meter_cache.xml"
            // "-t", "Verbos.e"
        };

        System.out.println("Parsing parameters...");
        if (Settings.getParameters(args, settings) != 0) {
            throw new Exception("Failed to parse settings.");
        }

        GXSerial serial = (GXSerial) settings.media;
        GXDLMSReader reader = new GXDLMSReader(settings.client, serial, settings.trace, null);

        try {
            System.out.println("Connecting to DLMS Meter on " + serial.getPortName() + "...");
            serial.open();
            
            // Stabilization delay
            Thread.sleep(1000);
            
            // reader.disconnect();

            System.out.println("Initializing Connection (Handshake)...");
            reader.initializeConnection();
            
            System.out.println("Handshake successful! Connection established.");
            
            System.out.println("Reading Association View (Object List)...");
            
            boolean read = false;

            if (settings.outputFile != null && new File(settings.outputFile).exists()) {
                try {
                    GXDLMSObjectCollection c = GXDLMSObjectCollection.load(settings.outputFile);
                    settings.client.getObjects().addAll(c);
                    read = true;
                } catch (Exception ex) {
                    // It's OK if this fails.
                    System.out.print(ex.getMessage());
                }
            }
            if (!read) {
                reader.getAssociationView();
                if (settings.outputFile != null) {
                    settings.client.getObjects().save(settings.outputFile, new GXXmlWriterSettings());
                }
            }
            // GXDLMSObject obj = new GXDLMSObject();
		    // obj.setLogicalName("1.1.2.8.0.255");
            // Object object = reader.read(obj, 2);
			for(int i = 0; i < 5; i++) {
				GXDLMSObject obj = settings.client.getObjects().findByLN(ObjectType.NONE, "1.1.1.8.0.255");
				if (obj != null) {
					Object unit = reader.read(obj, 3);
					Object val = reader.read(obj, 2);
//					reader.showValue(2, val);
//					reader.showValue(3, unit);
					System.out.println("Description of object: " + obj.getDescription());

				} else {
					System.out.println("Error: OBIS code 1.1.2.8.0.255 not found in meter objects.");
				}
				Thread.sleep(1000);
			}

			reader.getProfileGenerics();
            reader.disconnect();

            // System.out.println("Read value: " + object);
        } catch (Exception e) {
            System.err.println("Communication Error: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            if (serial != null && serial.isOpen()) {
                System.out.println("Closing serial port.");
                serial.close();
            }
        }
    }
}