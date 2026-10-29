# Grand Strategy: 세계 군수 시장 v1.3 + 한국어 UI

대상: **kilerjunny/Unciv**, 검증 기준: `feature/military-market`의 군수 시장 v1.2, 커밋 `8f48c9f0c`.

## 구현

- 플레이어가 자신이 생산 가능한 군사 유닛을 도시별로 **판매 등록**한다. 자동 기준가(생산비 × 3, 60~100000G), 도시당 등록 1개.
- AI가 재정·전력·전쟁 상태를 고려해 플레이어 또는 다른 AI의 상품을 **발주**할 수 있다. AI마다 신규 주문 12턴 간격, 동시 계약 제한, 최소 금 보유량 및 예산 상한이 있다.
- AI 판매국은 생산 완료 시 자동 **인도하기**를 승인한다. 플레이어 판매국은 **인도하기** 버튼을 직접 눌러야 한다.
- AI 구매국은 인도 승인 후 자기 턴에 **자동 인도받기**를 실행한다. 플레이어 구매국은 직접 누른다.
- 인도받기 시 기존 생산 유닛의 소유권만 변경한다. 생산국의 배치 타일을 그대로 유지하고 유닛을 새로 생성하지 않는다.
- 발주 시 예치금 차감, 인도 완료 시 판매국에 지급. 생산 전 구매자가 취소하면 환불한다.
- 유닛 이름 및 국가·도시 이름은 Unciv의 **기존 번역 사전**을 활용해 표시한다. 사용자 정의 유닛에 번역 항목이 없으면 원문이 나올 수 있다.
- 게임 개요의 `Formations` 탭은 `편제`로 표시한다. 거래 기록 UI에서는 다른 AI끼리의 거래를 플레이어에게 보여주지 않는다.

**주의:** 타국 생산기지에 남은 구매국 유닛이 이동하려면 외교적 **국경 통행권**이 필요할 수 있다. 자동 AI 발주는 통행권이 있을 때만 허용한다. 기존 세이브의 필드는 기본값으로 처리되나 실게임 세이브·로드 검증은 아직 필요하다.

## 설치 절차 (Codespaces ZIP 업로드 없이 GitHub 웹에서 업로드)

1. Codespaces에서 정상 빌드된 v1.2 브랜치로 이동한다.
   ```bash
   cd /workspaces/Unciv
   git switch feature/military-market
   git switch -c feature/military-market-v13
   git push -u origin feature/military-market-v13
   ```
2. GitHub 웹의 `feature/military-market-v13` 브랜치에서 **Add file → Upload files**로 `grand_strategy_military_market_v13_ko.zip` 업로드 및 커밋.
3. Codespaces에서:
   ```bash
   cd /workspaces/Unciv
   git pull --ff-only origin feature/military-market-v13
   unzip -o grand_strategy_military_market_v13_ko.zip
   python3 gs_military_market_v13/install.py --check
   ```
4. **`CHECK OK` 출력 시에만**:
   ```bash
   python3 gs_military_market_v13/install.py --apply
   python3 gs_military_market_v13/install.py --verify
   ./gradlew :core:compileKotlin --no-daemon --console=plain
   ```
5. 성공하면 **7개의 Kotlin 소스만** 선택하여 커밋한다.
   ```bash
   git add \
     core/src/com/unciv/logic/GameInfo.kt \
     core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt \
     core/src/com/unciv/ui/screens/overviewscreen/EmpireOverviewScreen.kt \
     core/src/com/unciv/logic/market/MilitaryMarket.kt \
     core/src/com/unciv/logic/market/MilitaryMarketAI.kt \
     core/src/com/unciv/logic/market/MilitaryMarketListing.kt \
     core/src/com/unciv/ui/screens/overviewscreen/MilitaryMarketSection.kt
   git diff --cached --stat
   git commit -m "Expand military market AI trade and Korean UI v1.3"
   git push origin feature/military-market-v13
   ```
6. GitHub Actions의 Android build 워크플로에서 `feature/military-market-v13`을 골라 APK를 빌드한다.

## 실게임 테스트 체크리스트

1. 기존 v1.2 세이브 불러오기 → 기존 발주 계약 유지 여부
2. 내 도시 유닛 **판매 등록** → AI가 12턴 이상 진행한 뒤 구매 가능성 확인
3. AI가 발주하면 실제 도시 생산 대기열 추가 및 대금 예치
4. **내가 판매자:** 완공 후 `인도하기` → AI가 자기 턴에 자동 인도받기 → 판매 대금 지급
5. **내가 구매자:** AI 판매자 완공 → `인도받기` → 생산지에 남아 있는지 확인
6. AI 간 거래 시 불필요한 플레이어 알림·거래 기록 노출이 없는지 확인
7. Unciv 언어 한국어 설정에서 `투석기`, `그리스`, `편제` 등의 표시 확인
8. 50~100턴 플레이 시 예치금 중복 지급, AI 거래 폭주 여부 확인

## 롤백

사용자 v1.2 원본 브랜치 `feature/military-market`은 전혀 수정하지 않는다. 설치는 기존 소스를 `/workspaces/GS_military_backup/v13_날짜`에 백업한다. 설치 중 에러 시 7개 파일을 이전 상태로 롤백하도록 구성되어 있다. `git reset --hard` 또는 `git clean -fd`를 임의로 실행하지 않는다.

## 검증 범위

패치 사전검사/모의 적용/중복 적용 차단: 통과. Kotlin **전체 프로젝트 컴파일** 및 APK 실게임 동작: 이 패키지 제작 환경에서는 실행하지 못했으며, Codespaces에서 확인 필요.
