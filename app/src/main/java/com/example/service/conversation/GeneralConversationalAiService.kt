package com.example.service.conversation

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Service providing open-domain conversational intelligence (General AI Chatbot)
 * for technology, ideation, learning, science, reasoning, and everyday consultation in Persian.
 * Uses Firebase AI Logic (gemini-3.8-flash) when online with a reliable timeout and
 * graceful offline fallback to local knowledge & reasoning rules.
 */
class GeneralConversationalAiService {
    private val TAG = "GeneralAiService"

    private fun createModel(userContextSummary: String?) =
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content { text(buildSystemPrompt(userContextSummary)) }
        )

    /**
     * Answers general inquiries, brainstorms ideas, explains concepts, offers logical advice,
     * or engages in natural conversation using online Gemini AI.
     * Guaranteed zero-cost, no paid subscriptions, no hardcoded API keys.
     */
    suspend fun generateChatReply(
        userMessage: String,
        recentHistory: List<ChatMessage> = emptyList(),
        userContextSummary: String? = null
    ): String {
        val trimmed = userMessage.trim()
        if (trimmed.isBlank()) return "درود! من یادت هستم؛ چطور می‌توانم کمکتان کنم؟"

        // Online path: at most 2 attempts (1 initial + 1 controlled retry) with cancellation awareness
        var attempts = 0
        val maxAttempts = 2
        var lastException: Exception? = null

        while (attempts < maxAttempts) {
            attempts++
            try {
                return withTimeout(20_000L) {
                    executeOnlineTurn(trimmed, recentHistory, userContextSummary)
                }
            } catch (e: TimeoutCancellationException) {
                // A request timeout is a recoverable service failure, not cancellation of the parent UI.
                lastException = e
                Log.w(TAG, "Gemini request timed out after 20 seconds; returning a controlled fallback.")
                break
            } catch (e: CancellationException) {
                // Preserve cancellation initiated by the caller or lifecycle.
                throw e
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Gemini turn attempt $attempts failed. type=${e.javaClass.simpleName}")
                if (attempts < maxAttempts) {
                    kotlinx.coroutines.delay(1000L)
                }
            }
        }

        // Keep diagnostics useful without exposing request contents, credentials, or App Check tokens.
        val failure = lastException
        val failureType = failure?.javaClass?.simpleName ?: "UnknownException"
        Log.e(TAG, "Online Gemini request failed. type=$failureType", failure)

        // Local canned knowledge must never look like a successful Gemini response.
        val offlineFallback = checkDirectAdvisoryOrRoutine(trimmed) ?: getOfflineKnowledgeReply(trimmed)
        val diagnosticDetails = safeDiagnosticDetails(failure)
        val diagnosticNotice = buildString {
            append("\n\n")
            append("توجه: اتصال Gemini برقرار نشد؛ ")
            append("پاسخ بالا از بخش آفلاین برنامه است. ")
            append("کد تشخیص: ")
            append(failureType)
            if (failure is TimeoutCancellationException) append(" (TIMEOUT)")
            if (!diagnosticDetails.isNullOrBlank()) {
                append("\nجزئیات فنی: ")
                append(diagnosticDetails)
            }
        }
        if (offlineFallback != null) {
            return offlineFallback + diagnosticNotice
        }

        // Honest error reporting when no response could be generated; never mask as fake Gemini reply.
        return "متأسفانه در حال حاضر امکان برقراری ارتباط با Gemini وجود ندارد. لطفاً بعداً دوباره تلاش کنید." + diagnosticNotice
    }

    /**
     * Exposes a short, sanitized server diagnostic so failures can be distinguished
     * (for example App Check rejection, invalid API key, disabled API, or quota).
     * Never include request headers, tokens, or full stack traces in chat UI.
     */
    private fun safeDiagnosticDetails(error: Throwable?): String? {
        if (error == null) return null
        val raw = generateSequence(error) { it.cause }
            .take(3)
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(" | ")
        if (raw.isBlank()) return null

        return raw
            .replace(Regex("(?i)AIza[0-9A-Za-z_-]{20,}"), "[کلید مخفی حذف شد]")
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~-]+"), "Bearer [توکن حذف شد]")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(360)
    }

    private suspend fun executeOnlineTurn(
        trimmed: String,
        recentHistory: List<ChatMessage>,
        userContextSummary: String?
    ): String {
        // System behavior and user context belong in the model's systemInstruction,
        // not in the user/model conversation history.
        val model = createModel(userContextSummary)

        // 1. Filter out the current user message from prior history so it is not sent twice
        val priorHistory = if (recentHistory.isNotEmpty() &&
            recentHistory.last().sender == MessageSender.USER &&
            recentHistory.last().text.trim() == trimmed
        ) {
            recentHistory.dropLast(1)
        } else {
            recentHistory
        }

        // 2. Build properly alternating conversation turns for startChat
        // Keep only complete, correctly ordered user/model pairs. In particular, never
        // invent a model reply to carry the system prompt as conversation history.
        val historyContentList = mutableListOf<com.google.firebase.ai.type.Content>()
        val cleanPrior = priorHistory.takeLast(8).toMutableList()
        while (cleanPrior.isNotEmpty() && cleanPrior.first().sender != MessageSender.USER) {
            cleanPrior.removeAt(0)
        }

        var index = 0
        while (index + 1 < cleanPrior.size) {
            val userMessage = cleanPrior[index]
            val assistantMessage = cleanPrior[index + 1]
            if (userMessage.sender == MessageSender.USER &&
                assistantMessage.sender == MessageSender.ASSISTANT &&
                userMessage.text.isNotBlank() &&
                assistantMessage.text.isNotBlank()
            ) {
                historyContentList.add(content("user") { text(userMessage.text.trim()) })
                historyContentList.add(content("model") { text(assistantMessage.text.trim()) })
                index += 2
            } else {
                index += 1
            }
        }

        val chat = model.startChat(history = historyContentList)
        val response = chat.sendMessage(trimmed)
        val reply = response.text?.trim()
        if (reply.isNullOrBlank()) {
            throw IllegalStateException("Empty response received from Gemini model")
        }
        return reply
    }

    private fun buildSystemPrompt(userContextSummary: String?): String {
        val memoryBlock = if (!userContextSummary.isNullOrBlank()) "\nاطلاعات و ترجیحات کاربر:\n$userContextSummary\n" else ""
        return """
            شما «یادت» (Yadet) هستید؛ یک دستیار هوشمند، دانا، منطقی، دقیق، راهنما و همراه همه‌فن‌حریف به زبان فارسی.
            شما یک چت‌بات عمومی پیشرفته و مشاور برنامه‌ریزی هستید، نه یک سیستم خشکِ فرمانی یا ربات صرفاً ثبت یادآور.
            
            اصول رفتاری و هویت:
            ۱. لحن و سبک زبان: به زبان فارسی طبیعی، سلیس، روان، محترمانه و امروزی گفتگو کنید. پاسخ‌ها نه بیش از حد کوتاه و کلیشه‌ای باشند و نه پرگویی کسالت‌بار داشته باشند؛ بلکه عمق پاسخ باید متناسب با پیچیدگی پرسش کاربر باشد.
            ۲. تفکیک گفتگو و احساس از فرمان:
               - اگر کاربر احساسات، افکار، درددل، درد یا خستگی را بیان کرد (مانند: «امروز خیلی خسته‌ام و باید استراحت کنم» یا «حالم خوب نیست»)، با مهربانی، درک و همدلی انسانی گفتگو کنید و هرگز آن را به یادآور یا آلارم تبدیل نکنید.
               - اگر کاربر خبری درباره آینده داد (مانند: «فردا امتحان سختی دارم» یا «هفته آینده احتمالاً سفر می‌روم»)، آن را به عنوان یک رویداد در جریان مکالمه بپذیرید، به او روحیه دهید و راهکارهای مدیریت زمان یا کاهش استرس پیشنهاد دهید؛ اما بدون درخواست صریح کاربر اقدام به ساخت یادآور نکنید.
               - فقط در صورتی که کاربر صراحتاً و مشخصاً درخواست یادآوری کرد (مانند: «یادم بنداز»، «یادآور بذار»، «آلارم تنظیم کن»)، او را برای تنظیم یادآوری راهنمایی کنید.
            ۳. مشاوره منطقی و تحلیل‌گری: در موضوعات سلامت، خواب، مدیریت زمان، اهداف و عادات (مانند خوابیدن ۴ ساعت یا ورزش سنگین در نیمه‌شب)، پیامدهای علمی و منطقی را مؤدبانه شرح دهید و تصمیم نهایی را به کاربر بسپارید.
            ۴. استدلال و پاسخگویی چندمرحله‌ای: به سوابق گفتگوهای قبلی توجه کنید و در مکالمات چندمرحله‌ای زمینه قبلی را به یاد داشته باشید.
            ۵. عدم ادعای کذب: هرگز وانمود نکنید عملیاتی (مانند ایجاد، تغییر یا حذف یادآور در دیتابیس) را خودبه‌خود انجام داده‌اید مگر اینکه کاربر دستور فرمانی صریح داده باشد.
            $memoryBlock
        """.trimIndent()
    }

    /**
     * Checks if input matches specific reasoning & consultation questions (midnight workout, 4h sleep, waking vs exercise).
     */
    fun checkDirectAdvisoryOrRoutine(input: String): String? {
        val lower = input.lowercase().trim()

        // 1. Midnight workout advice
        if ((lower.contains("ورزش") || lower.contains("تمرین")) && (lower.contains("۱۲ شب") || lower.contains("12 شب") || lower.contains("نیمه شب") || lower.contains("آخر شب"))) {
            return "اگر منظورتان از ورزش، تمرینات سنگین بدنسازی یا هوازی است، ساعت ۱۲ شب به دلیل افزایش ضربان قلب، بالا رفتن دمای مرکزی بدن و ترشح هورمون‌های کورتیزول و آدرنالین می‌تواند چرخه خواب شبانه را مختل کند. پیشنهاد می‌کنم تمرین را در ساعات عصر (مثلاً ساعت ۶ تا ۸ شب) انجام دهید. با این حال اگر تنها زمان خالی شما نیمه‌شب است، حرکات کششی سبک یا یوگا مناسب‌تر خواهد بود. دوست دارید ساعت ۸ شب تنظیم کنم یا همان ۱۲ شب؟"
        }

        // 2. Sleep deprivation advice
        if (lower.contains("خواب") && (lower.contains("۴ ساعت") || lower.contains("4 ساعت") || lower.contains("کم بخوابم") || lower.contains("کم خوابی"))) {
            return "از نظر علمی و فیزیولوژیک، خواب کمتر از ۷ تا ۸ ساعت برای افراد بزرگسال در درازمدت موجب افت شدید تمرکز، تضعیف سیستم ایمنی و تحلیل قوای شناختی می‌شود. برای رسیدن به کارهای بیشتر، متدهای بهینه‌سازی زمان‌های بیداری (مثل حذف حواس‌پرتی‌ها و استفاده از تکنیک بلوک‌بندی زمانی) بسیار کارآمدتر از کم کردن خواب شبانه است."
        }

        // 3. Waking time vs workout consultation
        if ((lower.contains("بیدار") || lower.contains("پاشم")) && (lower.contains("ورزش") || lower.contains("تمرین")) && (lower.contains("چه ساعتی") || lower.contains("به نظرت"))) {
            return "اگر ساعت ۸ صبح بیدار می‌شوید، دو بازه زمانی بسیار عالی برای ورزش وجود دارد:\n۱. صبحگاهی: حدود ساعت ۹ تا ۹:۳۰ صبح (پس از حدود ۴۵ دقیقه بیداری و صرف یک میان‌وعده سبک مثل موز یا خرما).\n۲. عصرگاهی: بین ساعت ۵ تا ۷ عصر که دمای بدن و بازدهی عضلانی در بالاترین سطح روز قرار دارد.\nبه نظر شما کدام بازه با برنامه روزتان هماهنگ‌تر است تا برایتان یادآور بگذارم؟"
        }

        // 4. Too many reminders consultation
        if ((lower.contains("همه کارهای") && lower.contains("یادآور")) || lower.contains("برای همه کارهام یادآور")) {
            return "تنظیم تعداد زیادی یادآور در طول روز ممکن است باعث پدیده «خستگی اعلان» (Notification Fatigue) شود و اثربخشی هشدارها را کم کند. پیشنهاد من این است که ۳ کار اصلی و غیرقابل مذاکره روزتان (Most Important Tasks) را انتخاب کرده و برای آنها یادآور بگذارید و کارهای خرد را در قالب یک چک‌لیست پیگیری کنید."
        }

        return null
    }

    /**
     * Local offline Persian conversational and knowledge fallback covering
     * technology, science, productivity, health, psychology, and general reasoning.
     */
    fun getOfflineKnowledgeReply(input: String): String? {
        val normalized = input.trim().lowercase()
        if (normalized.startsWith("سلام") || normalized.startsWith("درود") ||
            normalized.contains("صبح بخیر") || normalized.contains("عصر بخیر") ||
            normalized.contains("شب بخیر") || normalized.contains("حالت چطوره") ||
            normalized.contains("خوبی") || normalized.contains("چطوری")
        ) {
            return "سلام! ممنون که پرسیدی. من آماده‌ام باهات گفتگو کنم یا در انجام کارهات کمکت کنم."
        }
        val direct = checkDirectAdvisoryOrRoutine(input)
        if (direct != null) return direct

        val lower = input.lowercase().trim()

        return when {
            // Science & Physics
            lower.contains("آسمان آبی") || (lower.contains("اسمان") && lower.contains("ابی")) ->
                "آبی بودن آسمان به دلیل پدیده «پراکندگی ریلی» (Rayleigh Scattering) است. نور خورشید از تمام رنگ‌های طیف مرئی تشکیل شده، اما طول موج نور آبی کوتاه‌تر از نورهای دیگر است. وقتی نور خورشید به مولکول‌های گاز در جو زمین برخورد می‌کند، نور با طول موج کوتاه‌تر (آبی و بنفش) بسیار بیشتر از سایر رنگ‌ها در تمام جهات پراکنده می‌شود و به همین دلیل آسمان را آبی می‌بینیم."

            lower.contains("نسبیت") || lower.contains("انیشتین") || lower.contains("اینشتین") ->
                "نظریه نسبیت آلبرت اینشتین از دو بخش تشکیل شده است:\n۱. نسبیت خاص (۱۹۰۵): بیان می‌کند که سرعت نور در خلاء برای تمام ناظران ثابت است و زمان و فضا مطلق نیستند، بلکه به سرعت حرکت ناظر بستگی دارند (اتساع زمان).\n۲. نسبیت عام (۱۹۱۵): گرانش را نه یک نیروی جاذبه، بلکه انحنای بافت فضا-زمان در اثر حضور جرم و انرژی تعریف می‌کند."

            lower.contains("کوانتوم") ->
                "فیزیک کوانتوم به بررسی رفتار ذرات در مقیاس‌های بسیار کوچک (اتم‌ها و زیراتم‌ها) می‌پردازد. دو اصل بنیادین آن عبارتند از: «برهم‌نهی» (که ذره می‌تواند همزمان در چند حالت باشد تا زمانی که مشاهده شود) و «درهم‌تنیدگی» (ارتباط آنی بین ذرات فارغ از فاصله آنها)، که پایه محاسبات کامپیوترهای کوانتومی امروزی را تشکیل می‌دهند."

            // Productivity & Life Management
            lower.contains("پومودورو") ->
                "تکنیک پومودورو یک متد مدیریت زمان است که روز کاری شما را به بازه‌های ۲۵ دقیقه‌ای تمرکز کامل (یک پومودورو) و ۵ دقیقه استراحت کوتاه تقسیم می‌کند. پس از هر ۴ پومودورو، یک استراحت طولانی‌تر (۱۵ تا ۳۰ دقیقه) در نظر گرفته می‌شود. این روش مانع خستگی ذهن و فرسودگی می‌شود."

            lower.contains("پارتو") || lower.contains("۸۰/۲۰") || lower.contains("80/20") ->
                "اصل پارتو (قانون ۸۰/۲۰) بیان می‌کند که در اکثر پدیده‌ها، تقریباً ۸۰ درصد نتایج حاصل ۲۰ درصد از علت‌ها یا تلاش‌ها هستند. در مدیریت زمان، شناسایی آن ۲۰ درصد فعالیت‌های کلیدی که ۸۰ درصد ارزش و موفقیت کاری شما را رقم می‌زنند، بالاترین سطح بهره‌وری را به همراه دارد."

            lower.contains("آیزنهاور") || lower.contains("ماتریس فوریت") ->
                "ماتریس آیزنهاور کارها را بر اساس دو معیار «فوریت» و «اهمیت» به ۴ بخش تقسیم می‌کند:\n۱. فوری و مهم: فوراً انجام دهید.\n۲. مهم ولی غیرفوری: برنامه‌ریزی و زمان‌بندی کنید (کلید پیشرفت پایدار).\n۳. فوری ولی غیرمهم: به دیگران واگذار کنید.\n۴. نه فوری و نه مهم: حذف کنید."

            lower.contains("تمرکز") || lower.contains("حواسم") || lower.contains("پرت") ->
                "برای افزایش تمرکز:\n۱. اعلان‌های غیرضروری گوشی را خاموش کنید.\n۲. از بلوک‌بندی زمانی (Time-blocking) استفاده کنید.\n۳. یک برگه کاغذ کنار دستتان بگذارید و فکرهای متفرقه را روی آن بنویسید تا بعداً به آنها رسیدگی کنید.\n۴. محیط کارتان را مرتب و تمیز نگه دارید."

            // Tech & Coding
            lower.contains("هوش مصنوعی") || lower.contains("ai") || lower.contains("چت‌بات") ->
                "هوش مصنوعی (AI) دانش ساخت سیستم‌هایی است که قادر به تقلید رفتارهای شناختی انسان مانند استدلال، یادگیری الگوها و درک زبان طبیعی هستند. مدل‌های زبانی امروزی بر پایه معماری ترنسفورمر (Transformer) ساخته شده‌اند و می‌توانند ارتباط معنایی متن‌ها را با دقتی بالا تحلیل و تولید کنند."

            lower.contains("کاتلین") || lower.contains("اندروید") ->
                "کاتلین (Kotlin) زبان رسمی و مدرن توسعه اندروید است که توسط JetBrains طراحی شده است. ویژگی‌های برجسته آن شامل ایمنی در برابر Null Pointer Exception، کدنویسی مختصر، پشتیبانی کامل از Coroutines برای همزمانی آسان، و سازگاری کامل با Jetpack Compose برای طراحی رابط‌های کاربری مدرن و واکنشی است."

            lower.contains("پایتون") || lower.contains("برنامه‌نویسی") ->
                "پایتون به دلیل سینتکس خوانا و کتابخانه‌های بسیار غنی در حوزه‌های هوش مصنوعی، یادگیری ماشین، اسکریپت‌نویسی خودکار و توسعه وب، انتخابی ایده‌آل است. اگر مایلید یادگیری را شروع کنید، پیشنهاد می‌کنم ابتدا مبانی الگوریتم، سپس ساختارهای داده (لیست، دیکشنری) و مفاهیم شی‌گرایی را تمرین نمایید."

            lower.contains("فلاتر") || lower.contains("ری‌اکت نیتnative") ->
                "فلاتر (Flutter) از زبان دارت و موتور رندر اختصاصی Skia/Impeller برای ساخت اپ‌های چندسکویی (Cross-platform) استفاده می‌کند، در حالی که توسعه نیتیو با کاتلین در اندروید بهترین عملکرد، دسترسی کامل به سخت‌افزار و هماهنگی ۱۰۰ درصدی با آخرین ابزارهای سیستم‌عامل را فراهم می‌آورد."

            // Health & Habits
            lower.contains("استرس") || lower.contains("اضطراب") || lower.contains("نگرانی") ->
                "برای مهار استرس لحظه‌ای:\n۱. تنفس جعبه‌ای (Box Breathing): ۴ ثانیه دم، ۴ ثانیه حبس، ۴ ثانیه بازدم و ۴ ثانیه حبس.\n۲. نگرانی‌هایتان را دقیق روی کاغذ بنویسید؛ نوشتن بار هیجانی مغز را کاهش می‌دهد.\n۳. تفکیک کنید چه مواردی در دایره کنترل شما هستند و چه مواردی نیستند."

            lower.contains("زبان انگلیسی") || lower.contains("یادگیری زبان") ->
                "راز یادگیری زبان انگلیسی استمرار روزانه است نه ساعت‌های طولانی یک‌باره. گوش دادن روزانه به پادکست‌های انگلیسی، خواندن مقالات متناسب با علایق‌تان، و استفاده از روش جعبه لایتنر برای مرور لغات در فواصل زمانی منظم، موثرترین شیوه است."

            lower.contains("کتاب") || lower.contains("مطالعه") ->
                "برای شروع مطالعه مستمر، روزانه تنها ۱۰ الی ۱۵ صفحه کتاب بخوانید. کتاب‌های پیشنهادی در حوزه رشد فردی و مدیریت کار:\n۱. «عادت‌های اتمی» اثر جیمز کلیر\n۲. «کار عمیق» اثر کال نیوپورت\n۳. «تفکر، سریع و کند» اثر دانیل کانمن."

            lower.contains("استارتاپ") || lower.contains("کسب و کار") ->
                "برای راه‌اندازی یک استارتاپ:\n۱. با یک درد واقعی یا نیاز شفاف بازار شروع کنید نه صرفاً یک ایده خام.\n۲. یک محصول با حداقل ویژگی‌های قابل ارائه (MVP) بسازید و سریعاً از مشتریان واقعی بازخورد بگیرید.\n۳. روی ارزش پیشنهادی یکتا (Unique Value Proposition) تمرکز کنید."

            lower.contains("سلام") || lower.contains("درود") ->
                "سلام و درود! من «یادت» هستم، دستیار هوشمند شما. امروز چطور می‌توانم در گفتگو، پاسخ به پرسش‌ها، ایده‌پردازی یا مدیریت برنامه‌هایتان به شما کمک کنم؟"

            lower.contains("حالت چطوره") || lower.contains("چطوری") || lower.contains("خوبی") ->
                "ممنونم، حالم عالی است و با اشتیاق آماده همراهی شما هستم! شما در چه حالید و روی چه برنامه‌ای کار می‌کنید؟"

            lower.contains("کی هستی") || lower.contains("چه کارهایی بلدی") || lower.contains("راهنما") ->
                "من «یادت» هستم؛ دستیار هوشمند شما با قابلیت‌های جامع:\n۱. پاسخ به پرسش‌های عمومی، علمی، فناوری، برنامه‌ریزی و ایده‌پردازی به زبان فارسی.\n۲. مدیریت هوشمند تمام قرارها و یادآورها: ثبت، حذف تک‌تک یا همگی («همه رو حذف کن»)، جابجایی («ساعت رو بکن ۱۰»)، و جلو/عقب انداختن.\n۳. تغییر تنظیمات برنامه با فرمان صوتی یا متنی («تم رو تاریک کن»، «پیش‌اعلان رو بکن ۳۰ دقیقه»).\n۴. حافظه بلندمدت برای به خاطر سپردن عادات و ساعات خواب و بیداری شما."

            else -> null
        }
    }
}
