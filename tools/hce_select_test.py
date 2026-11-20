"""
실기기 HCE 테스트용: PC에 연결한 USB NFC 리더기(ACR122U 등 PC/SC 호환)로
NFC Guard 데모 AID에 SELECT APDU를 보내 NfcGuardHceService.processCommandApdu()를 깨운다.

설치:  pip install pyscard   (Linux는 pcscd 서비스 필요: sudo apt install pcscd)
사용:  python tools/hce_select_test.py            # 한 번
       python tools/hce_select_test.py --repeat 5 --interval 4   # 4초 간격 5회 (디바운스 3초 고려)

폰을 리더기에 올려두고 실행 -> adb logcat 에서 "field detected via apdu" 로그와 위험도를 확인.
가방 안 시나리오: 폰을 얇은 가방/파우치에 넣은 채 리더기 위에 올리고 실행.
"""
import argparse
import time

from smartcard.System import readers

# android/app/src/main/res/xml/apduservice.xml 의 aid-filter 와 동일해야 함
DEMO_AID = bytes.fromhex("F04E464347554152D0")


def select_apdu(aid: bytes) -> list[int]:
    return [0x00, 0xA4, 0x04, 0x00, len(aid), *aid, 0x00]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--interval", type=float, default=4.0, help="초. 앱의 3초 디바운스보다 길게")
    args = parser.parse_args()

    available = readers()
    if not available:
        raise SystemExit("PC/SC 리더기를 찾지 못함 (USB 연결, pcscd 실행 여부 확인)")
    reader = available[0]
    print(f"리더기: {reader}")

    for i in range(args.repeat):
        conn = reader.createConnection()
        try:
            conn.connect()  # 폰이 리더기 위에 없으면 여기서 예외
            _, sw1, sw2 = conn.transmit(select_apdu(DEMO_AID))
            print(f"[{i + 1}/{args.repeat}] SELECT 응답 SW={sw1:02X}{sw2:02X} (9000이면 앱이 응답함)")
        except Exception as e:  # noqa: BLE001 - 테스트 도구라 원인만 출력
            print(f"[{i + 1}/{args.repeat}] 실패: {e}")
        finally:
            try:
                conn.disconnect()
            except Exception:  # noqa: BLE001
                pass
        if i + 1 < args.repeat:
            time.sleep(args.interval)


if __name__ == "__main__":
    main()
