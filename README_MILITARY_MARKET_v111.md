# Grand Strategy — 군수 시장 v1.1.1 (NextTurnAutomation 호환 수정)

이번 패치는 v1.3 `NextTurnAutomation`의 `GrandStrategyPerf.measure` 구조에 맞게 적용 위치를 수정합니다. 기존 v1.1 `--check` 오류 `early unit movement: expected one anchor, found 0`를 해결한 버전입니다. AI 자동 이동·도시 공략·정착민 호위가 인도 대기 유닛을 사용하지 않도록 처리합니다.

**이전 패치가 적용되지 않은 상태에서는 아래 명령만 진행하세요. Git 초기화나 브랜치 전환은 불필요합니다.**

```bash
cd /workspaces/Unciv
unzip -o grand_strategy_military_market_v111.zip
python3 apply_military_market_v11.py --check
python3 apply_military_market_v11.py --apply
./gradlew :core:compileKotlin --no-daemon --rerun-tasks
```

`--check`가 오류 없이 통과해야 `--apply`를 수행하세요. Kotlin 컴파일에 실패하면 오류 전문을 공유해 주세요. 이 ZIP은 기존 v1.1 파일을 같은 이름으로 덮어씁니다.

컴파일이 성공하면 `git status --short`로 수정 파일을 확인하고, 변경된 `core/src/` 내 Kotlin 파일만 커밋한 뒤 Android 빌드 workflow를 실행하세요. **현재 작성 환경에서는 Kotlin 전체 프로젝트를 빌드하지 못했으므로 컴파일/동작은 미검증입니다.**

---

# Grand Strategy — 세계 군수 시장 v1.1 (양측 인도 선택)

## 이번 변경의 핵심

발주 -> 생산 -> **판매국 '인도하기'** -> **구매국 '인도받기'** -> 생산기지에서 직접 출발.

- 생산은 판매국의 **기존 도시 생산 대기열**에서 실제로 진행된다.
- 완공되면 생산국 소유의 **그 유닛이** 생산 현장에 남는다. 다른 국가의 도시에 새로 생성하거나 순간이동시키지 않는다.
- 생산이 완료된 계약 유닛은 AI의 자동 이동 대상에서 제외된다. 판매국이 `인도하기`를 수행하면 구매국이 `인도받기`를 선택할 수 있다.
- 인도받기를 누를 때 원래 생산된 유닛 객체의 소유권을 `gift()`로 이전한다. 위치와 유닛 ID를 보존하며 즉시 이동력은 0으로 만든다(다음 턴 이동 가능).
- 판매국이 있는 영토에 구매국의 통행 권한이 없으면 **인도받기 불가**로 둔다. 통행 협정 등을 맺은 후 수령할 수 있다. 전쟁 중이거나 생산 유닛이 소실되면 계약 취소·환불한다.
- 주문 시 예치한 금은 인도받기 성공 때 생산국에 지급한다.
- 게임 저장/복제시 주문 상태와 완성 유닛 ID를 유지한다. 기존 자원 시장, 편제 시스템, 외교 AI는 그대로 둔다.

## 1차 범위 (중요)

현재 기능은 **플레이어 구매자 → AI 판매국** 발주다. 따라서 AI 판매국은 생산 완료 시 **'인도하기'를 자동 승인**하고, 플레이어는 **'인도받기'를 직접 클릭**한다.
`인도하기` 버튼과 수동 승인 로직은 플레이어가 판매국인 계약을 위한 기반으로 넣었지만, **플레이어 판매국에 대한 AI의 발주 기능은 아직 구현되지 않았다.** AI-플레이어 쌍방 군수 시장은 후속 개발 범위다.

## 기존 v1.0과 다른 점

기존 v1.0은 생산국 유닛을 제거하고 구매국 영토에 별도 유닛을 생성했다. **v1.1은 해당 동작을 폐기**하고 원래 생산된 유닛 자체를 소유권 이전한다.
이미 v1.0을 적용했다면 v1.1 설치 스크립트는 기존 v1.0 신규 Kotlin 파일의 원본 내용인 경우에만 안전하게 덮어쓴다. 직접 수정한 코드가 있으면 충돌을 알리고 중단한다.

## Codespaces 적용

**v1.0을 아직 적용하지 않았다면** 기존 v1.3 브랜치에서 시작한다. 업로드된 zip 이름은 `grand_strategy_military_market_v11.zip`이다.

```bash
cd /workspaces/Unciv
git status --short
git switch perf/profiler-v13-breakdown
git switch -c feature/military-market-v11
unzip -o grand_strategy_military_market_v11.zip
python3 apply_military_market_v11.py --check
python3 apply_military_market_v11.py --apply
./gradlew :core:compileKotlin --no-daemon
```

**이미 v1.0 브랜치에서 적용했다면** 해당 브랜치에서 새 브랜치를 만든 뒤 v1.1 파일을 업로드/적용한다.

```bash
git switch feature/military-market-v1
git switch -c feature/military-market-v11
unzip -o grand_strategy_military_market_v11.zip
python3 apply_military_market_v11.py --check
python3 apply_military_market_v11.py --apply
./gradlew :core:compileKotlin --no-daemon
```

컴파일 성공 시:

```bash
git add core/src/com/unciv/logic/GameInfo.kt \
  core/src/com/unciv/logic/city/CityConstructions.kt \
  core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt \
  core/src/com/unciv/ui/screens/overviewscreen/WorldMarketTradeSection.kt \
  core/src/com/unciv/ui/screens/overviewscreen/MilitaryMarketSection.kt \
  core/src/com/unciv/logic/market/MilitaryMarket.kt \
  core/src/com/unciv/logic/market/MilitaryMarketContract.kt
git commit -m "Implement two-stage delivery for military market"
git push -u origin feature/military-market-v11
```

GitHub Actions -> Android build -> Run workflow -> `feature/military-market-v11` -> APK Artifact.

## 테스트할 것

1. 이미 알고 있는 평화로운 AI 국가에 유닛 1기를 발주하고 생산 대기열에 들어간 것을 확인.
2. 생산 완료 후 *구매국 영토가 아니라 생산국의 도시/인접 타일에* 유닛이 실제 남는지 확인.
3. AI가 다음 턴에 해당 유닛을 자동으로 이동시키지 않는지 확인.
4. 구매국 거래 -> 군수 시장 -> 진행 주문에서 상태 `인도 승인됨`, 버튼 `인도받기`가 활성화되는지 확인.
5. 통행 허가가 없다면 수령 불가인지 확인. 국경 개방 후 수령 가능한지 확인.
6. 수령 후 원래 생산 위치에서 구매국 유닛으로 표시되는지, 다음 턴에 이동 가능한지 확인.
7. 금액 지급이 딱 1회인지, 생산 중 취소/전쟁/유닛 소실로 계약 종료 시 환불이 되는지 확인.
8. 저장/불러오기, 기존 자원 거래, 편제, 성능 진단을 회귀 테스트.

## 검증 상태 및 제한

이 ZIP은 소스 구조 분석으로 작성한 패치로, 작성 환경에서는 Android/Gradle 프로젝트를 실제로 빌드하지 않았다. **Kotlin 컴파일과 실제 게임 플레이는 Codespaces에서 확인 필요.**
별도 군수 허가증 기능은 아직 없다. 따라서 영토 접근을 허용하려면 기존 게임의 국경 개방 조건을 충족해야 한다. 해안·해상·종교·도시국가·특수 이동 규칙에 대해서는 추가 인도 실험이 필요하다.
또한 생산에 들어간 금은 생산 도중에는 환불 취소가 가능하지만, 유닛 완공 뒤에는 구매자 단독 취소를 허용하지 않는다. 무제한 환불 악용을 방지하기 위한 초안 규칙이다.
