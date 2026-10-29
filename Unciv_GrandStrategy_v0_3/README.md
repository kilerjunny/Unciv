# Unciv: Grand Strategy v0.3 — 실제 게임 소스 연동 패치 (검증 전)

기반 규칙 모드: https://github.com/kilerjunny/Tech-Stopper-_Renaissance-_

Unciv 원본 프로젝트: https://github.com/yairm210/Unciv
검토한 Unciv `master` 커밋 (2026-10-16): `a8b1e0d62b237af4dcaa79e49c3a041cae31b588`

## 지금 들어 있는 것

- `engine_patch/FormationManager.kt`: 독립 Kotlin 편제 데이터/연산 코드. 생성, 이동, 삭제, 배속, 자동 정리, 템플릿.
- `engine_patch/FormationOverviewTab.kt`: Unciv 제국 개요에 들어갈 새 **Formations/편제** 탭 화면 코드. 상위 부대를 누르면 안으로 들어감.
- `engine_patch/apply_to_unciv.py`: 로컬 Unciv 소스에 3개 파일을 수정하고 2개 파일을 추가하는 설치 스크립트.
- `tests/FormationManagerTest.kt`: 독립 로직 테스트.
- `demo/index.html`: 기존 v0.2 안드로이드 브라우저용 UI 시제품. 게임과 미연동.
- `android-build-workflow-example.yml`: Unciv 포크의 GitHub Actions에서 디버그 APK를 만드는 워크플로 예시.

## 로컬 PC에서 엔진 패치 적용 (예시)

```bash
git clone https://github.com/yairm210/Unciv.git
# 가능하면 위 검토 커밋을 체크아웃한 별도 개발 브랜치에서 시작하세요.
python3 engine_patch/apply_to_unciv.py ./Unciv
cd Unciv
./gradlew :android:assembleDebug
```

위 명령은 이 패치 ZIP을 압축 해제한 폴더에서 실행하는 예시입니다. `engine_patch` 폴더와 `Unciv` 폴더가 나란히 있어야 합니다.

### 앱 설치 전에

- 이 ZIP은 **APK가 아니며**, 현재 실제 Unciv 전체 Gradle 빌드를 돌려보지 못했습니다. 정식 작동은 **확인 필요**.
- 이 패치는 르네상스 Tech-Stopper JSON 파일을 건드리지 않습니다. 나중에 빌드한 Unciv 앱에서 해당 규칙 모드를 **별도로 활성화**해야 합니다.
- 기존 Unciv 정식 설치본과 디버그 APK는 서명이 달라 덮어쓰기 설치가 실패할 수 있습니다. 기존 저장 게임과 모드 파일을 먼저 백업하세요. 아무 백업 없이 정식 앱을 삭제하지 마세요.
- UI 탭은 `Formations` 라벨(기본 영문)을 사용하고 내부 버튼은 한국어로 표시됩니다.
- 멀티플레이 편제 동기화, 실제 Android 테스트, 최종 릴리스 서명은 **미검증**. 먼저 싱글 플레이에서 시험하세요.

## 패치가 수정하는 원본 Unciv 파일

1. `core/src/com/unciv/logic/civilization/Civilization.kt` — 문명별 편제 데이터 저장 및 깊은 복제
2. `core/src/com/unciv/ui/screens/overviewscreen/EmpireOverviewCategories.kt` — 새 `Formations` 탭 등록
3. `core/src/com/unciv/logic/civilization/managers/TurnManager.kt` — 턴 시작 시 사라진 유닛 참조 정리

추가하는 파일:

- `core/src/grandstrategy/formations/FormationManager.kt`
- `core/src/com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt`

**기존 게임 코드의 백업**: 수정 전 파일 옆에 `.grandstrategy.bak` 파일이 생성됩니다. 검증 실패 시 *어느 파일도 수정하지 않도록* 소스 패턴 검증을 먼저 수행합니다. 같은 파일을 여러 번 패치해도 중복 삽입하지 않도록 설계했습니다.

## UI 동작

- `전체 편제 → 해군성 → 동방함대 → 전위전대` 등으로 탐색
- 하위 편제 생성, 이름 변경, 삭제(실제 유닛은 삭제하지 않음), 상위 편제 변경
- 직속 유닛과 하위 편제 유닛 표시
- 미배속/다른 편제의 실제 유닛을 현재 편제로 배속/전속
- 해군 및 육군 템플릿 생성
- 문명별 편제 데이터는 `Civilization.grandStrategyFormations` 필드로 게임 저장본에 포함되도록 설계
- 유닛 삭제/소유권 변경은 탭 갱신 및 다음 턴 정리 시 연결 해제. 업그레이드 도중 발생하는 일시적 유닛 교체에서 편제 정보가 손실되지 않도록 `UnitManager.removeUnit` 직접 후킹은 피함

HMS Britannia/Victory/London 등의 HMS 이름은 기존 구상대로 제독 유닛에만 사용합니다. 편제 시스템은 개별 함선 이름을 자동으로 만들지 않습니다.

## 테스트 확인

- 순수 Kotlin 편제 관리 유닛 테스트: **통과**
- UI Kotlin 소스의 구문 컴파일(경량 API 스텁): **통과**
- 패치 스크립트 모의 Unciv 구조 적용/재적용, 백업: **통과**
- 실제 Unciv 저장/로드 및 Android Gradle 컴파일: **미실행**, 확인 필요

기술적 판단은 검토 당시의 Unciv 소스에 근거합니다. 새로운 Unciv 릴리스에서는 패치 대상 소스/빌드 설정이 달라질 수 있습니다.
