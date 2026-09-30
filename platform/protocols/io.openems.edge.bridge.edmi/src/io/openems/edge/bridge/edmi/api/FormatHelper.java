package io.openems.edge.bridge.edmi.api;

import com.atdigital.imr.EdmiDateTime;
import com.sun.jna.Structure;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class FormatHelper {
    private static final DateTimeFormatter EDMI_FORMATTER =
            DateTimeFormatter.ofPattern("yy-MM-dd'T'HH:mm:ss");
    public static EdmiDateTime.ByValue fromFormattedString(String dateTime) {
        if (dateTime == null || dateTime.isBlank()) {
            throw new IllegalArgumentException("dateTime must not be null or blank");
        }

        try {
            LocalDateTime parsed = LocalDateTime.parse(dateTime, EDMI_FORMATTER);

            return new EdmiDateTime.ByValue(
                    parsed.getYear() % 100,   // keep only yy
                    parsed.getMonthValue(),
                    parsed.getDayOfMonth(),
                    parsed.getHour(),
                    parsed.getMinute(),
                    parsed.getSecond()
            );
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid dateTime format. Expected yy-MM-ddTHH:mm:ss, got: " + dateTime, e
            );
        }
    }
    public static void main(String[] args){
        String dateTimeStr = "26-04-22T00:00:00";
        EdmiDateTime.ByValue dateTimeValue =  fromFormattedString(dateTimeStr);
        System.out.println(dateTimeValue.toString());
    }
}
