"""
IEC 60870-5-104 BESS Simulator
================================
Mô phỏng một BESS (Battery Energy Storage System) server IEC-104 
để test driver OpenEMS IEC-104 Bridge.

Sử dụng thư viện c104 (iec104-python) từ Fraunhofer-FIT-DIEN.

Cài đặt:
    pip install c104

Chạy:
    python bess_iec104_simulator.py

Server sẽ listen tại 0.0.0.0:2404 (chuẩn IEC-104).
IOA addresses khớp với Iec104EssConfig defaults.

Tín hiệu mô phỏng:
    IOA 1:  SOC (%)              - Float, dao động 20-95%
    IOA 2:  Active Power (W)     - Float, dao động -40000 → +40000
    IOA 3:  Reactive Power (var) - Float, dao động -5000 → +5000
    IOA 4:  Battery Voltage (V)  - Float, dao động 680-820V
    IOA 5:  Battery Current (A)  - Float, tính từ Power/Voltage
    IOA 6:  Battery Power (W)    - Float, = Active Power
    IOA 7:  Battery Temperature  - Float, dao động 20-45°C
    IOA 8:  Min Cell Voltage (mV)- Float, dao động 3200-3400mV
    IOA 9:  Max Cell Voltage (mV)- Float, dao động 3400-3650mV
    IOA 10: Min Cell Temp (°C)   - Float, dao động 18-35°C
    IOA 11: Max Cell Temp (°C)   - Float, dao động 30-50°C
    IOA 12: System Running       - SinglePoint (Boolean ON/OFF)
"""

import c104
import time
import math
import random
import signal
import sys
import os
from datetime import datetime

# Fix Windows console encoding
if sys.platform == "win32":
    os.system("chcp 65001 >nul 2>&1")
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

# ============================================================================
# CẤU HÌNH
# ============================================================================
SERVER_IP = "0.0.0.0"
SERVER_PORT = 2404
COMMON_ADDRESSES = [1, 2, 3]  # Mô phỏng 3 thiết bị khác nhau trên cùng 1 COM port

# Chu kỳ cập nhật data (giây)
UPDATE_INTERVAL = 2.0

# Chu kỳ report spontaneous (ms) - gửi tự động khi giá trị thay đổi
REPORT_MS = 2000

# ============================================================================
# IOA ADDRESSES (khớp với Iec104EssConfig defaults)
# ============================================================================
IOA_SOC = 1                   # State of Charge [%]
IOA_ACTIVE_POWER = 2          # Active Power [W]
IOA_REACTIVE_POWER = 3        # Reactive Power [var]
IOA_BATTERY_VOLTAGE = 4       # Battery Voltage [V]
IOA_BATTERY_CURRENT = 5       # Battery Current [A]
IOA_BATTERY_POWER = 6         # Battery Power [W]
IOA_BATTERY_TEMPERATURE = 7   # Battery Temperature [°C]
IOA_MIN_CELL_VOLTAGE = 8      # Min Cell Voltage [mV]
IOA_MAX_CELL_VOLTAGE = 9      # Max Cell Voltage [mV]
IOA_MIN_CELL_TEMPERATURE = 10 # Min Cell Temperature [°C]
IOA_MAX_CELL_TEMPERATURE = 11 # Max Cell Temperature [°C]
IOA_SYSTEM_RUNNING = 12       # System Running status

# ============================================================================
# BESS SIMULATION PARAMETERS
# ============================================================================
BESS_CAPACITY_WH = 100000    # 100 kWh
BESS_MAX_POWER_W = 50000     # 50 kW PCS
BESS_NOMINAL_VOLTAGE = 750   # 750V DC bus


class BessSimulator:
    """Mô phỏng trạng thái BESS thay đổi liên tục."""

    def __init__(self):
        self.soc = 65.0               # Initial SOC: 65%
        self.active_power = 0.0       # Initial: idle
        self.system_running = True
        self.time_start = time.time()
        self.cycle = 0

    def update(self):
        """Cập nhật trạng thái BESS mỗi chu kỳ."""
        self.cycle += 1
        t = time.time() - self.time_start

        # --- Active Power: mô phỏng charge/discharge cycle ---
        # Sin wave + random noise = giống thực tế
        base_power = BESS_MAX_POWER_W * 0.6 * math.sin(t * 0.02)  # ~5 phút/cycle
        noise = random.gauss(0, BESS_MAX_POWER_W * 0.05)
        self.active_power = round(base_power + noise, 1)

        # --- SOC: tính từ power ---
        # Positive power = discharge, negative = charge
        delta_energy_wh = (self.active_power * UPDATE_INTERVAL) / 3600
        delta_soc = (delta_energy_wh / BESS_CAPACITY_WH) * 100
        self.soc = max(5.0, min(98.0, self.soc - delta_soc))

        # --- Voltage: phụ thuộc SOC ---
        # 680V (SOC=0%) → 820V (SOC=100%)
        voltage = 680 + (self.soc / 100.0) * 140
        voltage += random.gauss(0, 2)  # noise

        # --- Current: I = P / V ---
        current = self.active_power / voltage if voltage > 0 else 0

        # --- Temperature: tăng khi power cao ---
        base_temp = 25.0
        power_ratio = abs(self.active_power) / BESS_MAX_POWER_W
        temp = base_temp + power_ratio * 20 + random.gauss(0, 0.5)

        # --- Cell Voltages ---
        avg_cell_v = voltage / 224  # 224 cells in series
        min_cell_v = (avg_cell_v - random.uniform(0.01, 0.05)) * 1000  # mV
        max_cell_v = (avg_cell_v + random.uniform(0.01, 0.05)) * 1000  # mV

        # --- Cell Temperatures ---
        min_cell_temp = temp - random.uniform(1, 5)
        max_cell_temp = temp + random.uniform(1, 5)

        # --- Reactive Power ---
        reactive = self.active_power * 0.1 * math.sin(t * 0.05)
        reactive += random.gauss(0, 200)

        # --- System Running: toggle mỗi 5 phút (giả lập maintenance) ---
        self.system_running = True  # Giữ running cho test, có thể thay đổi

        return {
            IOA_SOC: round(self.soc, 1),
            IOA_ACTIVE_POWER: round(self.active_power, 1),
            IOA_REACTIVE_POWER: round(reactive, 1),
            IOA_BATTERY_VOLTAGE: round(voltage, 1),
            IOA_BATTERY_CURRENT: round(current, 2),
            IOA_BATTERY_POWER: round(self.active_power, 1),
            IOA_BATTERY_TEMPERATURE: round(temp, 1),
            IOA_MIN_CELL_VOLTAGE: round(min_cell_v, 0),
            IOA_MAX_CELL_VOLTAGE: round(max_cell_v, 0),
            IOA_MIN_CELL_TEMPERATURE: round(min_cell_temp, 1),
            IOA_MAX_CELL_TEMPERATURE: round(max_cell_temp, 1),
        }


def main():
    print("=" * 70)
    print("  IEC 60870-5-104 BESS Simulator")
    print("  Mô phỏng tín hiệu BESS cho OpenEMS IEC-104 Driver")
    print("=" * 70)
    print(f"  Server:  {SERVER_IP}:{SERVER_PORT}")
    print(f"  Common Addresses: {COMMON_ADDRESSES}")
    print(f"  Update Interval: {UPDATE_INTERVAL}s")
    print(f"  BESS Capacity: {BESS_CAPACITY_WH/1000:.0f} kWh")
    print(f"  Max Power: {BESS_MAX_POWER_W/1000:.0f} kW")
    print("=" * 70)

    # --- Tạo IEC-104 Server ---
    server = c104.Server(ip=SERVER_IP, port=SERVER_PORT)

    # --- Tạo các Station (Common Addresses) ---
    stations_data = []

    float_ioas = [
        (IOA_SOC, "SOC [%]"),
        (IOA_ACTIVE_POWER, "Active Power [W]"),
        (IOA_REACTIVE_POWER, "Reactive Power [var]"),
        (IOA_BATTERY_VOLTAGE, "Battery Voltage [V]"),
        (IOA_BATTERY_CURRENT, "Battery Current [A]"),
        (IOA_BATTERY_POWER, "Battery Power [W]"),
        (IOA_BATTERY_TEMPERATURE, "Battery Temp [°C]"),
        (IOA_MIN_CELL_VOLTAGE, "Min Cell Voltage [mV]"),
        (IOA_MAX_CELL_VOLTAGE, "Max Cell Voltage [mV]"),
        (IOA_MIN_CELL_TEMPERATURE, "Min Cell Temp [°C]"),
        (IOA_MAX_CELL_TEMPERATURE, "Max Cell Temp [°C]"),
    ]

    for ca in COMMON_ADDRESSES:
        station = server.add_station(common_address=ca)
        points = {}

        for ioa, name in float_ioas:
            point = station.add_point(
                io_address=ioa,
                type=c104.Type.M_ME_NC_1,  # Short Floating Point
                report_ms=REPORT_MS
            )
            points[ioa] = point

        # System Running - using M_ME_NC_1 (float: 1.0=ON, 0.0=OFF)
        sp_point = station.add_point(
            io_address=IOA_SYSTEM_RUNNING,
            type=c104.Type.M_ME_NC_1,  # Float (1.0=ON, 0.0=OFF)
            report_ms=REPORT_MS
        )
        points[IOA_SYSTEM_RUNNING] = sp_point
        
        stations_data.append({
            'ca': ca,
            'station': station,
            'points': points,
            'simulator': BessSimulator()
        })
        print(f"  [Station {ca}] Đã tạo 12 data points mô phỏng BESS.")

    print("-" * 70)

    # --- Start Server ---
    server.start()
    print(f"\n✅ IEC-104 Server đang chạy tại {SERVER_IP}:{SERVER_PORT}")
    print("   Đang chờ kết nối từ OpenEMS driver...")
    print("   Nhấn Ctrl+C để dừng.\n")

    # --- Simulation Loop ---
    running = True

    def signal_handler(sig, frame):
        nonlocal running
        print("\n\n⛔ Đang dừng server...")
        running = False

    signal.signal(signal.SIGINT, signal_handler)

    try:
        while running:
            now = datetime.now().strftime("%H:%M:%S")
            print(f"[{now}] ", end="")
            
            for sdata in stations_data:
                ca = sdata['ca']
                simulator = sdata['simulator']
                points = sdata['points']

                # Cập nhật giá trị mô phỏng
                values = simulator.update()

                # Ghi giá trị vào các float data points
                for ioa, value in values.items():
                    points[ioa].value = float(value)

                # System Running (float: 1.0=ON, 0.0=OFF)
                points[IOA_SYSTEM_RUNNING].value = 1.0 if simulator.system_running else 0.0

                # --- Console Output ---
                soc = values[IOA_SOC]
                power = values[IOA_ACTIVE_POWER]
                mode = "DIS" if power > 0 else "CHA" if power < 0 else "IDL"
                
                print(f"| CA={ca:d} {mode} P:{power:6.1f}W SOC:{soc:4.1f}% ", end="")
            
            print() # Xuống dòng cho chu kỳ tiếp theo
            time.sleep(UPDATE_INTERVAL)

    except Exception as e:
        print(f"\n❌ Error: {e}")

    finally:
        server.stop()
        print("✅ Server đã dừng.")


if __name__ == "__main__":
    main()
