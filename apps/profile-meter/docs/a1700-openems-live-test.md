# Elster A1700 live test through OpenEMS

## Runtime configuration

The checked-in test configuration creates:

- `iec6205621-serial0` on `/dev/ttyUSB0`, 9600 baud, 7 data bits, even parity,
  one stop bit;
- `a1700-meter0` using outstation `005` and password level 3;
- the read-write REST/JSON endpoint at `http://127.0.0.1:8084`.

Automatic reads are disabled in this test configuration. Every serial exchange is
therefore caused by an explicit API request.

## Start OpenEMS

From the `Integrate-MaxiEMS` directory:

```bash
bash gradlew :io.openems.edge.application:run.EdgeApp --console=plain
```

Wait until the REST controller, IEC bridge and A1700 component are active. Keep
this terminal open.

## Read current values

```bash
curl -sS -X POST http://127.0.0.1:8084/rest/jsonrpc \
  -H 'Content-Type: application/json' \
  -d '{
    "method": "componentJsonApi",
    "params": {
      "componentId": "a1700-meter0",
      "payload": {"method": "readA1700Current", "params": {}}
    }
  }' | python3 -m json.tool
```

## Read profile for one date

```bash
curl -sS -X POST http://127.0.0.1:8084/rest/jsonrpc \
  -H 'Content-Type: application/json' \
  -d '{
    "method": "componentJsonApi",
    "params": {
      "componentId": "a1700-meter0",
      "payload": {
        "method": "readA1700Profile",
        "params": {"date": "2026-09-23"}
      }
    }
  }' | python3 -m json.tool
```

For an inclusive range, replace `date` with:

```json
{"fromDate": "2026-09-20", "toDate": "2026-09-23"}
```

Dates are meter-local calendar dates in `YYYY-MM-DD` format.

## How date selection works

The selector is a recent-day count, not an ISO date. The A1700 was tested on
28 September 2026: `W1 551001(0A00)` returned 94 blocks beginning at
19 September 2026 00:00 and ending with data from 28 September. Historical PMU
captures also show `0200` returning two consecutive days.

For a requested date or inclusive range, OpenEMS calculates
`days = today - fromDate + 1` using the Edge machine's local date. For example,
on 28 September a request for 19 September sends `0A00`. It reads the returned
block count from `R1 551001(02)`, reads only that selected window from
`R1 550001(40)` onward, then filters decoded interval timestamps to the
requested dates. The response includes `data.profileQuery` with the original
and returned interval counts. The Edge clock and timezone must agree with the
meter's calendar date. Future dates and ranges requiring more than 255 days
are rejected because the selector's high-byte encoding is unverified.

Supplying an explicit native `selector` overrides this calculation for
diagnostics; the requested date filter still applies. With no date and no
selector, the meter's configured profile selector is used.
