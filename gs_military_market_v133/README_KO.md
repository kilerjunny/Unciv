# Grand Strategy 군수 시장 v1.3.3 (AI·고유 병종·생산 턴 표시)

v1.3 기준 **통합 패치**입니다. v1.3.2 미적용 상태에서도 적용됩니다. 이미 v1.3.2를 적용한 경우에도 적용할 수 있습니다.

## 변경 내용

- v1.3.2: AI 구매 기준 완화, 판매자 시장 진단, 고유 병종 ★ 표기 및 우선 표시.
- v1.3.3: 상단 현재 게임 턴 표시; 내 발주/납품 계약에 생산 완료까지 남은 예상 턴; 판매 등록 상품·판매 후보·다른 국가 상품에 예상 제작 턴.
- 기존 Unciv `CityConstructions.turnsToConstruction`을 주문이 생성된 계약에 사용. 앞선 생산 대기열 건도 예상치에 합산.
- 발주 전에는 도시 현재 생산력을 사용해 가볍게 예측하므로 실제 생산 조건과 생산력 변화에 따라 달라질 수 있습니다.
- 인도 중/완료 후에는 생산 남은 턴을 표시하지 않습니다.

## 적용

`feature/military-market-v13` 브랜치로 전환하고, ZIP을 GitHub에서 업로드 후 Codespaces에서 `git pull` 하세요.

```bash
unzip -o grand_strategy_military_market_v133_ai_unique_turns.zip
python3 gs_military_market_v133/install.py --check
python3 gs_military_market_v133/install.py --apply
python3 gs_military_market_v133/install.py --verify
./gradlew :core:compileKotlin --no-daemon --console=plain
```

컴파일 성공 후 아래 3개 파일만 커밋하세요.

```bash
git add core/src/com/unciv/logic/market/MilitaryMarket.kt core/src/com/unciv/logic/market/MilitaryMarketAI.kt core/src/com/unciv/ui/screens/overviewscreen/MilitaryMarketSection.kt
git commit -m "Add market AI, unique units and production turn estimates"
git push origin feature/military-market-v13
```

**주의**: 컴파일 테스트는 Codespaces에서 실행해야 하며, ZIP 설치 전 `--check`에서 예상 밖 소스 상태가 감지되면 작업을 중단합니다. 번역과 생산 턴 수의 실게임 동작은 APK 테스트가 필요합니다.
