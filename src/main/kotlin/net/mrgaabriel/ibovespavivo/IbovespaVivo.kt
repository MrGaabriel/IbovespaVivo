package net.mrgaabriel.ibovespavivo

import com.github.kevinsawicki.http.HttpRequest
import com.google.gson.Gson
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.SendMessage
import mu.KotlinLogging
import net.mrgaabriel.ibovespavivo.config.Config
import net.mrgaabriel.ibovespavivo.utils.url
import org.jsoup.Jsoup
import twitter4j.Twitter
import twitter4j.TwitterFactory
import twitter4j.conf.ConfigurationBuilder
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object IbovespaVivo {

    val logger = KotlinLogging.logger {}

    lateinit var config: Config

    var lastRate: Double
        get() = File("last_rate.txt").readText().toDouble()
        set(value) = File("last_rate.txt").writeText(value.toString())

    @JvmStatic
    fun main(args: Array<String>) {
        val file = File("config.json")

        if (!file.exists()) {
            logger.warn { "Parece que é a primeira vez que você iniciou o bot! Configure-o no arquivo \"config.json\"" }

            file.createNewFile()
            file.writeText(Gson().toJson(Config()))

            return
        }

        // MOMENTO GAMBIARRA
        val lastRateFile = File("last_rate.txt")
        if (!lastRateFile.exists()) {
            lastRateFile.createNewFile()
            lastRateFile.writeText("0")
        }

        config = Gson().fromJson(file.readText(), Config::class.java)

        val twitter = if (config.twitter.enabled)
            setupTwitter()
        else
            null

        val telegram = if (config.telegram.enabled)
            setupTelegram()
        else
            null

        while (true) {
            val rate = fetchRate()

            logger.info { "Rate: $rate" }
            logger.info { "É diferente de $lastRate? ${lastRate != rate}" }

            if (lastRate == rate) {
                Thread.sleep(3 * 60 * 1000)
                continue
            }

            logger.info { "Atualizando status!" }

            val now = OffsetDateTime.now()
            val timestamp = now.format(DateTimeFormatter.ofPattern("HH:mm"))

            val msg = if (rate > lastRate)
                "↗ $rate pontos - $timestamp"
            else
                "↘ $rate pontos - $timestamp"

            twitter?.updateStatus(msg)?.also {
                logger.info { "Postado no Twitter! ${it.url()}" }
            }

            telegram?.execute(SendMessage("@${config.telegram.channel}", msg))?.also {
                logger.info { "Enviado no Telegram!" }
            }

            lastRate = rate
            logger.info { "lastRate = $lastRate" }

            Thread.sleep(3 * 60 * 1000)
        }
    }

    fun fetchRate(): Double {
        val request = HttpRequest.get("https://www.investing.com/indices/bovespa")
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/75.0.3770.54 Safari/537.36")

        if (!request.ok())
            throw RuntimeException()

        val jsoup = Jsoup.parse(request.body())

        val element = jsoup.selectFirst("#last_last")
        val priceText = element.text().replace(",", "")
        val price = priceText.toDoubleOrNull() ?: throw RuntimeException()

        return price
    }

    fun setupTwitter(): Twitter? {
        return try {
            val twitterConfig = ConfigurationBuilder()

            twitterConfig.setOAuthConsumerKey(config.twitter.consumerKey)
                .setOAuthConsumerSecret(config.twitter.consumerSecret)
                .setOAuthAccessToken(config.twitter.accessToken)
                .setOAuthAccessTokenSecret(config.twitter.accessSecret)

            val factory = TwitterFactory(twitterConfig.build())
            factory.instance
        } catch (e: Exception) {
            null
        }
    }

    fun setupTelegram(): TelegramBot? {
        return try {
            TelegramBot(config.telegram.token)
        } catch (e: Exception) {
            null
        }
    }
}
