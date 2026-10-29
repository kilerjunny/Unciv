# Grand Strategy 모바일 — 세계 군수 시장 통합 패치 v1.2 (개발 브랜치 전용)

> 기준: `kilerjunny/Unciv`의 `feature/military-market`, 기반 커밋 `66a949cf7` (Grand Strategy profiler v1.3).  
> **주의:** 이 ZIP을 푸는 것만으로 기존 게임 코드는 바뀌지 않습니다. ZIP은 최상위 `gs_military_market_integrated_v12/` 폴더만 설치합니다.

## 설계 및 현재 구현 범위

- **발주(플레이어)**: 알고 있고 전쟁 중이 아닌 AI 문명의 생산 가능한 군사 유닛을 주문. 그 AI 문명의 도시 생산 대기열에 1기가 실제로 추가됩니다.
- **대금 예치**: 발주 시 구매국의 금을 차감하고, 완료 후 *인도받기*를 승인하면 판매국에 지급. 생산 중 구매국 취소 / 공급자 패배 / 부대 소멸 등 조건에서 환불합니다.
- **생산 완료**: 생산국 도시에서 원래 생성된 `MapUnit`의 ID를 보관. 새 유닛을 구매국 영토에 만들거나 순간이동시키지 않습니다.
- **인도하기(판매국 승인)**: 생산국이 승인하면 주문 상태가 수령 대기로 바뀝니다. 이 1차 버전의 **AI 판매국은 자동 승인**합니다.
- **인도받기(구매국 수동)**: 구매자가 군수 시장 탭에서 직접 선택해야 소유권이 변경됩니다. 같은 유닛이 생산지에 남고 이동력은 0으로 처리되어 다음 턴부터 이동합니다.
- **통행 조건**: 생산 위치가 외국 영토이고 구매국이 그 영토를 통행할 수 없다면 수령 버튼이 비활성화됩니다. 거래국과 국경 개방 협정 등을 맺어야 할 수 있습니다.
- **AI 보존**: 완공 후 인도 대기 중인 유닛은 AI 일반 자동 이동·도시 공략·정착민 호위에 사용되지 않도록 해당 주요 경로에서 제외합니다.
- **저장 지원**: 계약, 상태, 생산 유닛 ID, 최근 100건 계약 내역을 `GameInfo`에 저장·복제합니다.

**명확한 제한:** 플레이어가 판매국이 되어 직접 `인도하기`를 누르는 상태와 버튼 코드는 넣었지만, **AI가 플레이어에게 먼저 발주하는 기능은 이번 통합 v1.2에 아직 없습니다**. 그러므로 실제 일반적인 플레이에서는 `AI의 인도하기(자동) → 플레이어의 인도받기(수동)`으로 동작합니다. AI→플레이어 발주와 인간 판매국 수동 인도는 후속 개발입니다. 수동 '인도하기'가 즉시 완전 작동한다고 오해하지 마세요.

## 설치 — 반드시 신규 브랜치에서

이미 `feature/military-market` 생성·원격 푸시가 끝났다면 새 브랜치를 만들 필요가 없습니다.

1. `grand_strategy_military_market_integrated_v12.zip`을 Codespaces의 `/workspaces/Unciv`에 업로드합니다.
2. 다음 명령을 **하나씩** 실행합니다.

```bash
cd /workspaces/Unciv
unzip -o grand_strategy_military_market_integrated_v12.zip
python3 gs_military_market_integrated_v12/install.py --check
```

**정확히 `CHECK OK`가 나와야만** 다음으로 넘어갑니다. `PATCH ERROR`면 멈추고 오류 내용을 보여 주세요. 특히 `WorldMarketTradeSection.kt` 등의 수정 내역이 남아 있으면 원본과 다를 수 있습니다.

```bash
python3 gs_military_market_integrated_v12/install.py --apply
python3 gs_military_market_integrated_v12/install.py --verify
./gradlew :core:compileKotlin --no-daemon --console=plain --rerun-tasks
```

`--check`는 읽기 전용, `--apply`는 전체 소스·위치 검사를 마친 뒤 백업을 만들고 한 번에 적용합니다. 적용 도중 오류가 발생하면 복원하도록 작성했습니다. **`BUILD SUCCESSFUL`은 실제 Codespaces 실행으로 확인해야 합니다**. 이 채팅 환경에서는 원격 소스 전체를 내려받아 Gradle로 빌드하지 못했기 때문에 컴파일/실플레이는 아직 검증하지 못했습니다.

## 컴파일 통과 후 푸시

**저장소 루트의 다른 `files/`, 오래된 ZIP, 구버전 apply 파일은 커밋하지 마세요.** 아래에 나열된 게임 Kotlin 파일만 Git에 넣습니다.

```bash
git status --short

git add \
 core/src/com/unciv/logic/GameInfo.kt \
 core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt \
 core/src/com/unciv/logic/city/CityConstructions.kt \
 core/src/com/unciv/ui/screens/overviewscreen/WorldMarketTradeSection.kt \
 core/src/com/unciv/ui/screens/overviewscreen/MilitaryMarketSection.kt \
 core/src/com/unciv/logic/market/MilitaryMarket.kt \
 core/src/com/unciv/logic/market/MilitaryMarketContract.kt

git diff --cached --stat
git commit -m "Add integrated two-stage military procurement market"
git push origin feature/military-market
```

GitHub Actions → **Android build** → Run workflow → 브랜치 `feature/military-market` 선택 → APK 생성·설치.

## 시험하기

1. 거래 탭 → 세계 시장 → **군수 시장**. 평화 상태이며 서로 알고 있는 AI 국가가 있다면 주문 가능 유닛과 금액이 보이는지 확인합니다.
2. 발주하면 예치금이 차감되고 AI 도시 생산 큐에 추가되는지 확인합니다.
3. 완료 후 **인도 승인됨 · 인도받기 대기** 상태인지 확인하고 유닛이 생산국 기지에 남는지 봅니다.
4. 통행 허가가 없다면 수령이 제한되는지 확인합니다. 통행 허가 후 **인도받기**를 눌러 기존 유닛 ID/위치가 유지되면서 소유국이 변경되는지 확인합니다.
5. 저장·불러오기, 취소/환불, 전쟁/유닛 소멸, 기존 세계 자원 시장·편제·외교 기능을 각각 확인합니다.

## 복구 안내

인스톨러는 적용 전의 4개 소스 파일을 `/workspaces/GS_military_backup/integrated_v12_...` 폴더에 따로 저장합니다. 기존 v1.3 브랜치는 그대로 보존되어 있습니다.

문제가 발생하면 **우선 원인 로그를 보존**하고, 패치를 다시 덧씌우거나 `git clean -fd`를 실행하지 마세요. 필요한 경우 새 브랜치의 수정된 4개 추적 파일만 `git restore -- ...`로 되돌리고, 새로 추가된 3개의 군수 시장 `.kt`만 안전하게 백업 폴더로 옮기면 v1.3 상태로 되돌릴 수 있습니다.
