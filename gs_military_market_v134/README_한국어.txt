Unciv Grand Strategy 군수예산 보호 v1.3.4

대상: feature/military-market-v13 브랜치 + v1.3.3 패치까지 커밋한 버전

설계:
1) AI가 군수품을 구매할 가능성이 있으면 가장 싼 실제 납품 가능 유닛의
   가격을 바탕으로 구매 가능해질 때까지 국고 일부를 보존합니다.
2) 군수 구매를 세계 자원 시장 구매보다 먼저 판단합니다.
3) 일반 자원 구매, 건물 즉시 구매, 타일 구매, 도시국가 후원이 이 예산을 침해하지 않습니다.
4) 유닛 업그레이드는 군사 지출로 취급하므로 예산보다 우선할 수 있습니다.
5) 전쟁 중 또는 평시 군사력이 부족할 때만 적립합니다.
6) 거래 후보, 통행권, 판매국의 생산 능력 등 실제 구매 조건을 만족하지 않으면 적립하지 않습니다.
7) 기존 게임 저장 데이터 형식에 필드 추가가 없으며 AI 전용으로 적용합니다.
8) 평시 가격 350G 이하, 전시 550G 이하의 구매 가능 상품만 저축 대상으로 간주합니다.

설치:
  unzip -o grand_strategy_military_market_v134_budget.zip
  python3 gs_military_market_v134/install.py --check
  python3 gs_military_market_v134/install.py --apply
  python3 gs_military_market_v134/install.py --verify
  ./gradlew :core:compileKotlin --no-daemon --console=plain

컴파일 성공 시 Git 반영:
  git add core/src/com/unciv/logic/market/MilitaryMarketAI.kt \
          core/src/com/unciv/logic/market/WorldMarketAI.kt \
          core/src/com/unciv/logic/automation/civilization/UseGoldAutomation.kt \
          core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt
  git diff --cached --stat
  git commit -m "Reserve AI treasury for military procurement"
  git push origin feature/military-market-v13

주의:
- 실제 Kotlin 컴파일과 Android 게임 테스트는 Codespaces에서 확인해야 합니다.
- 할인/가격/인도 권한은 기존 v1.3.3 규칙을 유지합니다.
- 금액 예약은 은행 계좌 분리나 신규 자원이 아니라 AI 일반 소비의 보호 한도입니다.
