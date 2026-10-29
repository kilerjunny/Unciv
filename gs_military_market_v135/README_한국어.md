# Grand Strategy / Unciv 군수 시장 v1.3.5

**대상:** `feature/military-market-v13`, v1.3.4 국방예산 패치를 적용한 코드 (컴파일 성공한 버전).

## 변경 사항

1. **도시국가가 군수품 판매·구매에 참여합니다.** AI 도시국가는 실제로 건설 가능한 유닛을 자동으로 판매 목록에 올립니다. 구매 능력이 있는 AI 도시국가는 직접 주문하며, 생산·인도 후 기존 소유권 이전 로직으로 수령합니다.
2. **AI 구매 조건 완화:** 신규 군수 발주 간격 12턴→6턴, 평시 군사력 상한 `도시 수 × 5`→`max(8, 도시 수 × 8)`, 글로벌 동시 계약 24건→32건.
3. **구매 예산 확대:** 평시 현금의 최대 80%, 전시 90%까지 군수 구매 예산. 단, 주요 문명 최소 50G, 도시국가 최소 25G를 남깁니다. 기존 국방예산 보호 로직도 동일한 구매 수치에 맞게 조정합니다.
4. **도시국가 군수상품 노출:** AI 공급 목록에서 도시국가를 먼저 검사하여 100개 표시 한도에 가려질 가능성을 낮춥니다. 판매국이 실제 생산할 수 없는 유닛은 여전히 판매할 수 없습니다.
5. **진단:** 플레이어 판매 목록의 `AI 구매 진단`에 도시국가 후보 수를 별도로 표시합니다.

## 반드시 유지되는 조건

- 교전 중인 상대국과 거래할 수 없습니다.
- **구매국이 판매국 생산기지에서 유닛을 인도받고 직접 이동할 수 있는 통행 권한**이 있어야 AI가 발주합니다. 이 제약은 유닛이 생산국 영토에 갇히는 것을 막는 안전장치입니다. 군수품 한정 임시 통행 허가 기능은 *이번 버전에 포함되지 않습니다*.
- AI 신규 발주는 국가당 한 번에 한 계약, 이전 발주 이후 최소 6턴, 전 세계 동시 계약 최대 32건으로 제한합니다.
- 도시국가도 실제 보유 금·생산 가능 목록·외교 조건을 충족해야 참여합니다.

## 설치

GitHub에서 `feature/military-market-v13` 브랜치에 ZIP을 업로드해 커밋한 뒤, Codespaces 터미널에서:

```bash
cd /workspaces/Unciv
git pull --ff-only origin feature/military-market-v13
unzip -o grand_strategy_military_market_v135_citystates.zip
python3 gs_military_market_v135/install.py --check
python3 gs_military_market_v135/install.py --apply
python3 gs_military_market_v135/install.py --verify
./gradlew :core:compileKotlin --no-daemon --console=plain
```

설치 스크립트는 지정된 브랜치, 기존 v1.3.4 소스 내용, 패치 적용 결과를 확인하고 **두 Kotlin 파일만 수정**합니다. 이미 적용된 패치는 중복 적용하지 않으며, 작업 전 파일을 `/workspaces/GS_military_backup/v135_...`에 백업합니다.

**주의:** GitHub에 업로드한 ZIP 자체는 게임 코드에 적용되지 않습니다. 위 스크립트가 성공하고 컴파일도 성공해야 소스를 커밋해야 합니다. 실제 프로젝트 Kotlin 컴파일과 모바일 APK 실행은 제작 환경에서 미검증입니다.

컴파일 후 커밋:

```bash
git add core/src/com/unciv/logic/market/MilitaryMarket.kt core/src/com/unciv/logic/market/MilitaryMarketAI.kt
git commit -m "Enable city-state military trade and relax AI procurement"
git push origin feature/military-market-v13
```

## 실게임 테스트

- 다른 국가의 판매 목록에 도시국가의 생산 가능 유닛이 나오는지 확인
- 플레이어 장궁병을 판매 등록하고 `AI 구매 진단 ... (도시국가 N국 포함)` 확인
- 통행권·현금·군사 수요가 있는 도시국가가 신규 발주하는지 확인
- AI 도시국가가 생산 후 `인도하기`를 자동 처리하고 구매자가 실제 동일한 유닛을 생산기지에서 인도받는지 확인
- 국방예산이 외교·도시 지출에 의해 소진되지 않는지 확인
