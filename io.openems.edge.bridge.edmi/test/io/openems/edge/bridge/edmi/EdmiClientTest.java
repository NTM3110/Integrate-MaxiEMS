package io.openems.edge.bridge.edmi; // Update to your actual component's test package

import com.atdigital.imr.EdmiDateTime;
import com.atdigital.imr.objects.ReportProfileData;
import com.fazecast.jSerialComm.SerialPort;
import org.junit.Test;
import com.atdigital.imr.MeterClient; // Import your library's class
import com.atdigital.imr.ImrCoreLib;
import com.atdigital.imr.EdmiRegisters;

import java.io.Serial;
import java.util.List;

/**
 * This test verifies the integration and basic initialization of the IMR EDMI Client.
 */
public class EdmiClientTest {
    @Test
    public void testImports() {
        // Simple test to ensure the IMR EDMI wrapper library is successfully loaded
        try {
            MeterClient client = new MeterClient("COM11", 9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            System.out.println("IMR EDMI Client initialized successfully!");
            
            // If your MeterClient has getters, you can test them here, like:
            // System.out.println("Client status: " + client.toString());
            
        } catch (Exception e) {
            System.err.println("Failed to initialize MeterClient: " + e.getMessage());
            e.printStackTrace();
        }
    }
    @Test
    public void testRun(){
        try{
            MeterClient.listPorts();
            ImrCoreLib.INSTANCE.Init();
            System.out.println("IMR.Core initialized.");

            MeterClient client = new MeterClient("COM11", 9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            client.connect();

            if (!client.login("EDMI", "IMDEIMDE", 251308613)) {
                System.out.println("Login failed — check COM port, baud rate, serial, credentials.");
                return;
            }

            List<Object> billingValue =  client.readBillingValues();
            System.out.println("Billing values: ");
            for (Object obj : billingValue){
                System.out.println(obj.toString());
            }

            short survey = 0x0305;
            // Query from 2026-01-01 to open-ended (null = "to end of available data")
            // This returns all records the DLL has buffered, including today's intervals.
            EdmiDateTime.ByValue from = new EdmiDateTime.ByValue(26, 6, 16, 0, 0, 0);
            EdmiDateTime.ByValue to = new EdmiDateTime.ByValue(26, 6, 17, 0, 0, 0);
            ReportProfileData reportProfileData = (ReportProfileData) client.readProfile(survey, from, to);
            System.out.println(reportProfileData.toString());

        } catch (Exception e) {
            System.err.println("Failed to RUN TEST" + e.getMessage());
            e.printStackTrace();
        }

        
    }
}
