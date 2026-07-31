# خريطة المشروع — SecureMessenger

**آخر تحقّق:** 2026-07-31
**المنهجية:** كل رقم وكل ادّعاء هنا قِيس أو قُرئ من الملف الفعلي في هذا التاريخ. ما لم أتحقّق منه في هذه الجولة مُعلَّم صراحةً بـ«غير مُعاد التحقّق» بدل تمريره كأنه حاضر. الأرقام المقيسة تستثني `build/` و`node_modules/` و`.gradle/` و`.kotlin/` ما لم يُذكر خلاف ذلك.

**علاقة هذا الملف بغيره:** `SECURITY_AUDIT.md` هو المرجع للأمن، وهذا الملف للبنية والجرد. عند التعارض بينهما في مسألة أمنية، `SECURITY_AUDIT.md` هو الأحدث والأدقّ.

---

## 1. شجرة المجلد الأعلى

```
Desktop\تطبيق مراسلة\
├── SecureMessenger\            ← المشروع الفعلي
├── prompt_kotlin_compose.md    ← برومبت عربي لتوليد التطبيق (8.7 ك.ب، مرجع تاريخي)
├── .claude\settings.local.json ← قائمة أذونات أوامر gradle/adb لهذه الجلسات
├── .idea\                      ← إعدادات Android Studio (7 ملفات)
├── .wrangler\cache\            ← wrangler-account.json (انظر §7)
└── .uploads\                   ← فارغ
```

```
SecureMessenger\
├── settings.gradle.kts   → include(":core", ":app", ":desktop", ":hisn")
├── build.gradle.kts      → AGP 8.13.2 · Kotlin 2.0.21 · KSP 2.0.21-1.0.26 · Compose MP 1.7.1
├── gradle.properties     → overridePathCheck · JDK=Android Studio JBR · relayUrl
├── core\      :core     مكتبة Kotlin/JVM — البروتوكول المشترك
├── app\       :app      تطبيق أندرويد   com.securemessenger.app
├── desktop\   :desktop  عميل Compose Desktop
├── hisn\      :hisn     تطبيق أندرويد منفصل  com.hisn.app
├── relay\               Cloudflare Worker (TypeScript) — خارج Gradle
├── docs\adr\            قرار معماري واحد
├── README.md · SECURITY_AUDIT.md · PROJECT_MAP.md
└── security_test.py · memory_test.py   (سكربتان مساعدان، غير مربوطين بالبناء)
```

---

## 2. الوحدات ومسؤولياتها

| الوحدة | النوع | المسؤولية |
|---|---|---|
| `:core` | مكتبة Kotlin/JVM (`java-library`) | كل ما **يجب أن يتطابق بايت ببايت** بين الهاتف والكمبيوتر: الراتشيت، التغليف، الترميز، الحشو، رموز الصندوق. |
| `:app` | تطبيق أندرويد | التطبيق الرئيسي، متخفٍّ باسم **«حاسبة متقدمة»**. |
| `:desktop` | Compose Desktop | نِدّ مستقل بهويته الخاصة — ليس مرآة للهاتف، يقترن بالـQR مثل أي هاتف. |
| `:hisn` | تطبيق أندرويد مستقل | **«حصن»** — مدقّق نظافة أمنية للجهاز بدون root. APK منفصل، لا علاقة كودية بالمراسل. |
| `relay/` | Cloudflare Worker | **صندوق بريد أعمى** — مسار احتياطي لمن ليس على الشبكة المحلية. |

**`:hisn` لا يعتمد على `:core` ولا على `:app`** — هو في نفس مستودع Gradle فقط، وحدوده مستقلة تماماً.

---

## 3. تعداد الكود (مقيس 2026-07-31)

| الوحدة | ملفات Kotlin (مصدر) | ملفات Kotlin (اختبار) | مجموع kt | أسطر (kt+xml+kts+ts) |
|---|---|---|---|---|
| `:app` | 57 | 9 | **66** | 14,636 |
| `:desktop` | 9 | 1 | **10** | 2,438 |
| `:core` | 14 | 0 | **14** | 1,722 |
| `:hisn` | 14 | 3 | **17** | 1,569 |
| `relay/` | — | — | 1 ملف `.ts` | 227 |
| **المجموع** | **94** | **13** | **107 kt + 1 ts** | **20,592 سطر** |

ملفات أخرى: 17 XML · 6 Gradle KTS · 7 Markdown · 2 Python · 5 ملفات في `relay/` (بلا `node_modules`).

### أكبر عشرة ملفات

| الأسطر | الملف |
|---|---|
| 1,335 | `app/network/SecureMessagingClient.kt` |
| 1,030 | `app/data/repository/SecureRepository.kt` |
| 727 | `app/ui/screens/chat/ConversationScreen.kt` |
| 642 | `app/ui/screens/chat/MediaContent.kt` |
| 632 | `desktop/DesktopMessagingClient.kt` |
| 605 | `app/ui/screens/settings/SettingsScreen.kt` |
| 512 | `app/ui/screens/chat/ContactDetailScreen.kt` |
| 421 | `core/crypto/SignalProtocol.kt` |
| 414 | `desktop/DesktopStore.kt` |
| 406 | `desktop/MainScreen.kt` |

---

## 4. محتوى `:core` — البروتوكول المشترك (14 ملف)

| المجلد | الملفات |
|---|---|
| `crypto/` | `SignalProtocol.kt` (Double Ratchet + X3DH) · `PqKem.kt` (ML-KEM-768) · `LibsodiumWrapper.kt` · `MailboxToken.kt` · `AckToken.kt` · `MessagePadding.kt` · `MediaCodec.kt` · `ChatPayloads.kt` |
| `net/` | `LocalRelayServer.kt` · `Envelopes.kt` · `LanAddress.kt` · `RelayBlob.kt` |
| الجذر | `B64.kt` · `Platform.kt` |

**قرار تصميمي مهم:** libsodium هو `compileOnly` في `:core`. الأندرويد يشحن `lazysodium-android` والكمبيوتر يشحن `lazysodium-java`، وكلاهما يحوي نفس أصناف `com.goterl.lazysodium`؛ الاعتماد على أحدهما في `:core` كان سيُدخل نسخة مكرّرة على المنصة الأخرى. النسخة الفعلية تُحقَن عند الإقلاع عبر `Platform.installSodium(...)`.

كذلك `okhttp` و`nanohttpd` و`org.json` و`coroutines` كلها `api(...)` لا `implementation(...)` — لأنها في السطح العلني للوحدة (`LocalRelayServer` **هو** `NanoWSD`، و`Envelopes` تأخذ `JSONObject`). و`:app` يستثني `org.json` لأن أندرويد يوفّره في bootclasspath.

---

## 5. النقل (Transport) — الحالة الفعلية

النقل **هجين، بمسارين متزامنين ومختلفَي القواعد عمداً**:

### 5.1 المسار المحلي — WebSocket على الشبكة المحلية ✅ قائم

- كل جهاز يشغّل خادمه الخاص: `core/net/LocalRelayServer.kt` (81 سطر، يرث `NanoWSD`).
- الاكتشاف: mDNS/DNS-SD على `_securemessenger._tcp.` — `NsdManager` على أندرويد، jmDNS على الكمبيوتر.
- الاسم المُعلَن `sm-<token>` حيث `token = blake2b(identityPublicKey)` مُدوَّر كل ساعة، فلا يُبَثّ مفتاح هوية على الشبكة.
- عناوين مباشرة مُتعلَّمة/يدوية كمسار ثانٍ حين يفشل mDNS.
- سقف 20 اتصالاً متزامناً، وسقف إطار 12 م.ب.
- **النص صريح (`ws://`) عمداً** — انظر `app/src/main/res/xml/network_security_config.xml`: النِّد عنوان IP متغيّر لا اسم نطاق، ولا سلطة تصديق تُصدر شهادة له؛ والحمولة مشفّرة بالكامل في طبقة التطبيق قبل أن تلمس المقبس.
- مستخدموه الفعليون: `app/network/SecureMessagingClient.kt` · `desktop/DesktopMessagingClient.kt` · `desktop/test/LoopbackMessagingTest.kt`.

### 5.2 المسار الاحتياطي — الصندوق الأعمى ✅ منشور وحيّ

- الكود: `relay/src/index.ts` (227 سطر). المفردات كلها: `POST /d` إيداع، `POST /f` تحصيل. لا حسابات، لا أسماء، لا مفاتيح، لا مفهوم «محادثة»، ولا health endpoint.
- معرّف الصندوق 16 بايت يشتقّه الطرفان من سرّ تبادلاه عبر QR، **يُدوَّر كل ساعة**، ولكل اتجاه معرّف مختلف.
- 32 Durable Object، التوزيع بالبايت الأول من المعرّف فقط — أي تجميع لصناديق محادثة واحدة في shard واحد كان سيسلّم الخدمة نفس الربط الذي وُجد التدوير لإخفائه.
- TTL 48 ساعة · سقف الإيداع 200 ك.ب · 256 حمولة للصندوق · 48 معرّفاً للتحصيل · 32 عنصراً للإيداع.
- التحصيل **مُتلِف**: يُسلَّم ويُحذف، ولا يُسجَّل أن قراءة حدثت.
- `observability` **مُطفأة عمداً** في `wrangler.jsonc`.
- `RelayClient.kt` يضيف فوق ذلك: إعادة تشفير الظرف الخارجي، حشو لفئات حجم موحّدة، تجزئة الرسائل الكبيرة، حركة تمويه اختيارية، تأخير عشوائي، وسحب دوري (polling) بدل مقبس مفتوح — لأن الاشتراك الدائم كان سيخبر الوسيط أن هذه الصناديق كلها لجهاز واحد.
- **حالة النشر (مُتحقَّق منها 2026-07-31):** `GET https://sm-blind-mailbox.basil0552106933.workers.dev/` → **405 MethodNotAllowed** — وهو بالضبط ما يعيده `index.ts:192` لغير POST، أي أن المنشور هو هذا الكود.
- `network_security_config.xml` يمنع النص الصريح على `workers.dev` صراحةً، عكس المسار المحلي.

### 5.3 ما يراه الوسيط رغم كل ذلك

عنوان IP لمن يتصل، ولحظة اتصاله. لا شيء في الكود يغيّر ذلك — التوجيه عبر Tor أو VPN وحده يفعل. مذكور صراحةً في تعليق `index.ts` وفي `RelayClient.kt`.

### 5.4 `relayUrl` — مفتاح التبديل

`gradle.properties` يحوي حالياً:
```
relayUrl=https://sm-blind-mailbox.basil0552106933.workers.dev
```
**فالبناء الحالي يستخدم الوسيط.** إفراغ هذا السطر يُنتج بناءً **بلا أي مسار وسيط إطلاقاً** — شبكة محلية فقط، لا شيء يغادر الـLAN. هذا هو الإعداد الأشدّ، وهو خيار قصدي متروك للمستخدم لا افتراض.

---

## 6. الاعتماديات

| الفئة | المكتبات |
|---|---|
| التشفير | lazysodium-android 5.2.0 / lazysodium-java 5.1.4 · JNA 5.17.0 · BouncyCastle 1.81 (ML-KEM) |
| التخزين | Room 2.6.1 + KSP · sqlcipher-android 4.9.0 · DataStore 1.0.0 · security-crypto 1.1.0-alpha06 |
| الواجهة | Compose BOM 2023.10.01 · Material3 · material-icons-extended · Navigation 2.7.5 · Compose MP 1.7.1 |
| الشبكة | OkHttp 4.12.0 · nanohttpd-websocket 2.3.1 · jmDNS 3.5.9 (الكمبيوتر) |
| أخرى | ZXing 3.5.2 · biometric 1.1.0 · WorkManager 2.9.0 · Coroutines 1.7.3 |
| الوسيط | wrangler ^4 · TypeScript ^5.7 · @cloudflare/workers-types |

### ملاحظة على الإصدارات (سؤال متكرّر)

**Compose BOM 2023.10.01 مع Kotlin 2.0.21 تركيبة صحيحة ومدعومة، وليست سهواً.** منذ Kotlin 2.0 صار مُصرِّف Compose يُشحن ضمن إضافة `org.jetbrains.kotlin.plugin.compose` ومنفصلاً عن BOM مكتبات Compose، فلا يلزم `composeOptions { kotlinCompilerExtensionVersion }` — وفعلاً **لا وجود لها في أي ملف gradle في المشروع**، وهو الصواب.

البقاء على BOM قديم (مكتبات 1.5.4) قرار تأجيل معروف لا خطأ: ترقيته مطلوبة لميزة واحدة محدّدة (`platformImeOptions` لمنع تعلّم لوحة المفاتيح)، وأُجّلت لأن ترقية BOM تمسّ كل شاشة.

---

## 7. الملفات الحسّاسة والمولَّدة

| الملف | الحالة الفعلية |
|---|---|
| `.wrangler\cache\wrangler-account.json` (في الجذر) | يحوي مفتاحاً واحداً اسمه `account` — **معرّف حساب، ليس رمز API**. لا يُرفع إلى مستودع عام، لكنه ليس سرّاً قابلاً للاستغلال بذاته. |
| `relay\.wrangler\tmp\` | **فارغ**. موضع ثانٍ لنفس اسم المجلد؛ الانتباه له مهم عند كتابة `.gitignore`. |
| `local.properties` | مسار Android SDK المحلي. لا يُرفع. |
| `gradle.properties` | يحوي `relayUrl` و`org.gradle.java.home` بمسار محلي. |
| `build/` · `.gradle/` · `.kotlin/` | **حُذفت 2026-07-31** (428.8 م.ب). تُولَّد كلها من جديد عند أول بناء، دون شبكة، لأن كاش Gradle العام في `~/.gradle/caches` باقٍ. |
| `relay/node_modules/` | **يبقى — 190.2 م.ب. قرار صريح من المالك (2026-07-31): لا يُحذف.** السبب: استعادته تحتاج `npm install` أي **اتصال شبكة**، بخلاف مخرجات Gradle التي تُولَّد بلا شبكة من الكاش العام. لا تقترح حذفه. |

---

## 8. الاختبارات

| الموقع | ملفات | المحتوى |
|---|---|---|
| `app/src/androidTest` | 9 | `SignalProtocolTest` · `RatchetRoundtripTest` · `PqKemTest` · `MetadataPrivacyTest` · `AckAuthenticationTest` · `RelayRoundtripTest` · `CryptoSecurityTests` · `SecurityConfigurationTests` · `QrImageTest` |
| `hisn/src/androidTest` | 3 | `AuditLogicTest` · `VerifyImportTest` · `NotificationTest` |
| `desktop/src/test` | 1 | `LoopbackMessagingTest` |
| سكربتات مساعدة | 2 | `security_test.py` · `memory_test.py` (تُشغَّل يدوياً، ليست جزءاً من البناء) |

**لماذا لا توجد اختبارات وحدة JVM في `:app`:** كل اختبار فيه يمسّ libsodium، ومكتبته الأصلية لا تُحمَّل خارج تشغيل على جهاز — فكلها في `androidTest` عمداً.

### كيف تُشغَّل

```bash
gradlew :app:connectedDebugAndroidTest     # يحتاج جهازاً/محاكياً
gradlew :hisn:connectedDebugAndroidTest    # يحتاج جهازاً/محاكياً
gradlew :desktop:testDirect                # ← وليس :desktop:test
```

**`gradlew :desktop:test` لا يعمل من هذا المسار.** Gradle يمرّر مسار الأصناف إلى عامل الاختبار عبر ملف مولَّد يقرأه العامل بترميز النظام الافتراضي، وهو على ويندوز صفحة ترميز قديمة لا UTF-8 — فكل مدخل يحوي حرفاً عربياً يفكّ إلى مسار غير موجود، وتفشل كل الاختبارات بـ`ClassNotFoundException` رغم أنها تُصرَّف سليمة. `testDirect` مهمة `JavaExec` تمرّر المسار على سطر الأوامر مباشرة فتتجاوز المشكلة. نقل المشروع إلى مسار إنجليزي يُلغي الحاجة إليها.

### آخر نتيجة تشغيل موثَّقة

| المجموعة | النتيجة | حالة التحقّق |
|---|---|---|
| `:desktop:testDirect` | **OK (3 tests)** في 4.6 ثانية | ✅ **شُغِّلت فعلاً في جولة 2026-07-31** |
| `:app:connectedDebugAndroidTest` | 27/27 | 📄 منقولة عن `SECURITY_AUDIT.md` (2026-07-09، Galaxy S25 Ultra / API 36) — **لم تُعَد هنا** |
| `:hisn:connectedDebugAndroidTest` | 24/24 | 📄 كسابقتها — **لم تُعَد هنا** |

مجموعتا `connectedAndroidTest` تحتاجان جهازاً موصولاً، ولم يكن متاحاً في هذه الجولة. الرقمان يبقيان أفضل ما هو موثَّق، لا نتيجة مُثبَتة اليوم.

تحذيرات ظهرت أثناء تشغيل `testDirect` وهي **متوقَّعة لا أعطال**: `mDNS unavailable` و`port 47601 unavailable` (نسختا الاختبار تعملان على نفس الجهاز فتتنازعان المنفذ، والاحتياط بمنفذ يخصّصه النظام يعمل) و`peer socket error` عند إغلاق المقبس في نهاية الاختبار.

---

## 9. مخرجات البناء (مقيسة 2026-07-31)

> **الملفات أدناه لم تعد موجودة على القرص.** قِيست ثم حُذفت في نفس الجولة ضمن تنظيف المساحة (انظر §7 و§11.2). الأرقام محفوظة هنا لأنها الجواب على «لماذا حجم حصن كهذا»، ويعاد إنتاجها بأي بناء جديد.

| المخرَج | الحجم | ملاحظة |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | **43.87 م.ب** | dex مضغوط: 10.7 م.ب مضغوط من 40.6 خام · مكتبات أصلية 20.5 م.ب لأربع معماريات |
| `hisn/build/outputs/apk/debug/hisn-debug.apk` | **48.98 م.ب** | أُعيد بناؤه 2026-07-31 |
| `core/build/libs/core.jar` | 0.08 م.ب | |
| `desktop/build/libs/desktop.jar` | 0.28 م.ب | |

### لماذا حصن ضخم رغم اعتمادياته القليلة

تركيب `hisn-debug.apk` بعد إعادة البناء:

| الجزء | ملفات | الحجم |
|---|---|---|
| **DEX** | 7 | **48.53 م.ب (99%)** |
| كل ما عداه (`resources.arsc` + `res` + `kotlin` + `META-INF` + المانيفست) | 141 | 0.42 م.ب |

السبب المهيمن: **`androidx.compose.material:material-icons-extended`** — ملف الـAAR وحده **32 م.ب** في كاش Gradle، وهو عشرات آلاف أصناف الأيقونات، و`isMinifyEnabled = false` في debug فلا يُقلَّم منها شيء. الحلّ إن أُريد: استيراد الأيقونات المستخدمة فعلاً بدل الحزمة الكاملة، أو تفعيل التقليم — كلاهما تغيير كود خارج نطاق هذه الوثيقة.

سبب ثانٍ يفسّر كونه **أكبر من تطبيق المراسل** رغم أن الأخير يحمل libsodium وSQLCipher: dex المراسل **مضغوط** (10.7 من 40.6)، بينما dex حصن **مخزَّن بلا ضغط** (48.5 من 48.5).

> **تصحيح مسجَّل:** كان الملف المقيس سابقاً 56.24 م.ب بتاريخ 2026-07-04 — أي أثر بناء قديم بائت. المصدر الحالي يُنتج **48.98 م.ب**. الفارق (~7.3 م.ب) لم يعد قابلاً للتحليل: إعادة البناء استبدلت الملف القديم، فلا سبيل لمقارنته بعد الآن. حصة الـdex كانت ~48.5 م.ب في النسختين، فالفارق كان خارجها.

---

## 10. تصحيحات لجرد سابق

| ما قيل سابقاً | الحقيقة المتحقَّقة |
|---|---|
| «`.wrangler` يحوي بيانات حساب Cloudflare حسّاسة» | يحوي **معرّف الحساب فقط**، لا رمز API. ولا يزال لا يُرفع، لكن الوصف كان مبالَغاً فيه. وهناك موضعان لا واحد (الجذر + `relay/`). |
| «الوسيط قديم/غير مستخدم» | **حيّ ومنشور ومستخدَم فعلاً** — تحقّق بـ405 على GET، و`relayUrl` مضبوط. |
| «WebSocket أُلغي» | **قائم وحيّ** للشبكة المحلية. الخطأ في README عكسيّ: ليس أن WebSocket ذهب، بل أن وصفه كـ«over TLS 1.3 + certificate pinning» غير صحيح. |
| «حصن 56 م.ب» | 56.24 كان أثراً بائتاً من 2026-07-04. المصدر الحالي → **48.98 م.ب**. |
| «تعارض إصدارات Compose/Kotlin» | لا تعارض. التركيبة مدعومة، والغياب التامّ لـ`composeOptions` هو الصواب تحت Kotlin 2.0+. |
| تعداد الملفات | مؤكَّد صحيحاً: 107 ملف Kotlin بالتوزيع في §3. |

---

## 11. مسائل مفتوحة (تشخيص، لا إصلاح)

هذه مذكورة للعلم؛ معالجتها تغييرٌ في المشروع خارج نطاق هذه الوثيقة.

1. ~~لا يوجد مستودع Git.~~ **حُلَّ 2026-07-31.** المستودع مُهيَّأ على الفرع `main` وأول commit هو `49a8d85` بـ153 ملفاً (1.16 م.ب). `.gitignore` كُتب **قبل** أول commit عن قصد، فلم يدخل التاريخ قطّ أي مخرَج بناء ولا `local.properties` ولا مفتاح توقيع ولا ملف حساب Cloudflare. ملف حساب Cloudflare خارج المستودع أصلاً (في المجلد الأب). **تنبيه دائم:** تعديل `.gitignore` لاحقاً لا يُخرج ملفاً دخل التاريخ فعلاً.
2. ~~نحو 600 م.ب مخرجات بناء.~~ **حُلَّ جزئياً 2026-07-31:** حُذف 428.8 م.ب من مخرجات Gradle (620.7 → 191.9 م.ب)، و`relay/node_modules` (190.2 م.ب) **يبقى بقرار صريح من المالك — لا يُحذف** (انظر §7). تُحقّق أن البناء لم ينكسر بـ`gradlew projects` → الوحدات الأربع تُحلّ سليمة. أول بناء لاحق سيكون أبطأ، وهي الكلفة المعروفة. **هذا البند مُغلق، لا معلَّق.**
3. **نصوص يتيمة في `strings.xml`** (لا مرجع لها في أي كود):
   - `app_name_decoy` · `decoy_mode_active` · `decoy_mode_description` — بقايا آلية «الوضع الوهمي» التي حُذفت وحلّ محلّها **رمز الطوارئ (duress code)**.
   - `stealth_mode_dialer` — وقيمته **`*#73287#` خاطئة**؛ الرمز الصحيح الذي يعرضه `StealthModeScreen` فعلاً ويستقبله المانيفست هو `*#*#73287#*#*`. غير مستخدم حالياً فلا أثر له على المستخدم، لكنه فخّ لمن يربطه لاحقاً بواجهة.
4. **ثغرة متوسطة معروفة ومؤجَّلة بوعي:** حقل `userId` ومفتاح الهوية العلني يمرّان **نصّاً صريحاً** في إطارَي `challenge`/`bundle_announce`، فمتنصّت على نفس الشبكة يستطيع ربط الجهاز عبر الجلسات رغم تدوير رمز الاكتشاف. التفصيل والمبرّر في `SECURITY_AUDIT.md §2`.
5. **`docs/adr/0001`** (عزل فكّ الوسائط في عملية منفصلة) بحالة **«مقترَح»** — مرجع تصميم لم يُنفَّذ.
6. **المسار العربي** يفرض `android.overridePathCheck=true` ومهمة `testDirect`. نقل المشروع إلى مسار إنجليزي يُلغي الحيلتين.
7. **`README.md`** كان يصف بنية ميتة حتى 2026-07-31؛ أُعيدت كتابته في نفس جولة هذه الوثيقة.
