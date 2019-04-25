package net.mrgaabriel.ibovespavivo

import com.github.kevinsawicki.http.HttpRequest
import com.github.salomonbrys.kotson.obj
import com.github.salomonbrys.kotson.string
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import twitter4j.Twitter
import twitter4j.TwitterFactory
import twitter4j.conf.ConfigurationBuilder
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.concurrent.thread

object AoVivoBot {

    val logger = KotlinLogging.logger {}

    lateinit var config: Config

    lateinit var twitter: Twitter

    @JvmStatic
    fun main(args: Array<String>) {
        logger.info { "Hello, World" }

        val file = File("config.json")

        if (!file.exists()) {
            file.createNewFile()
            file.writeText(
                Gson().toJson(
                    Config(
                        "Consumer Key",
                        "Consumer Secret Key",
                        "Access Token",
                        "Access Secret Token",
                        "API Key",
                        "Symbol"
                    )
                )
            )

            logger.warn { "Configure o bot em \"config.json\"!" }
            System.exit(0)
        }

        config = Gson().fromJson(file.readText(), Config::class.java)

        try {
            val twitterConfig = ConfigurationBuilder()

            twitterConfig.setOAuthConsumerKey(config.consumerKey)
                .setOAuthConsumerSecret(config.consumerSecret)
                .setOAuthAccessToken(config.accessToken)
                .setOAuthAccessTokenSecret(config.accessSecret)

            val factory = TwitterFactory(twitterConfig.build())
            twitter = factory.instance

            logger.info { "Conectado com sucesso!" }

            val lastPriceFile = File("last_price.txt")
            if (!lastPriceFile.exists()) {
                lastPriceFile.createNewFile()

                val quote = fetchQuote(config.symbol)
                lastPriceFile.writeText("${quote.price}")
            }

            thread(name = "Console Handler") {
                while (true) {
                    try {
                        val next = readLine()!!
                        val splitted = next.split(" ")

                        val cmd = splitted[0]

                        val args = splitted.toMutableList()
                        args.removeAt(0)

                        when (cmd.toLowerCase()) {
                            "tweet" -> {
                                val content = args.joinToString(" ")

                                val status = twitter.updateStatus(content)
                                logger.info { "Tweetado! https://twitter.com/${status.user.screenName}/status/${status.id}" }
                            }

                            "force_tweet_quote" -> {
                                val quote = fetchQuote(config.symbol)

                                tweetQuote(quote)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Erro!" }
                    }
                }
            }

            thread(name = "Tweet Stuff") {
                while (true) {
                    try {
                        val quote = fetchQuote(config.symbol)

                        tweetQuote(quote)
                    } catch (e: Exception) {
                        logger.error(e) { "Erro!" }
                    }

                    Thread.sleep(1000 * 60 * 3)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Erro!" }
        }
    }

    fun tweetQuote(quote: GlobalQuote) {
        val lastPriceFile = File("last_price.txt")
        val now = OffsetDateTime.now()

        val lastPrice = lastPriceFile.readText().toDouble()

        val price = quote.price

        if (price != lastPrice) {
            val format = DateTimeFormatter.ofPattern("HH:mm")
            val message = "${if (price > lastPrice) "↗" else "↘"} Ibovespa ${if (price > lastPrice) "subiu" else "caiu"}! ${quote.price} pontos - Às ${format.format(now)}"

            lastPriceFile.writeText("${quote.price}")

            logger.info { "Preço mudou! Preço: $price" }
            val status = twitter.updateStatus(message)
            logger.info { "Tweetado com sucesso! https://twitter.com/${status.user.screenName}/status/${status.id}" }
        }
    }

    fun fetchQuote(symbol: String): GlobalQuote {
        val request = HttpRequest.get("https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=$symbol&apikey=${config.apiKey}")
            .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/73.0.3683.75 Safari/537.36")

        if (!request.ok())
            throw RuntimeException("Request is not OK! Code: ${request.code()} - Body: ${request.body()}")

        val payload = JsonParser().parse(request.body()).obj

        return GlobalQuote(payload)
    }
}

class GlobalQuote(private val obj: JsonObject) {

    private val globalQuote = obj["Global Quote"].obj

    val symbol = globalQuote["01. symbol"].string

    val open = globalQuote["02. open"].string.toDouble()
    val high = globalQuote["03. high"].string.toDouble()
    val low = globalQuote["04. low"].string.toDouble()

    val price = globalQuote["05. price"].string.toDouble()

    val volume = globalQuote["06. volume"].string.toInt()

    val latestTradingDayRaw = globalQuote["07. latest trading day"].string
    val latestTradingDay: OffsetDateTime get() {
        val latestTradingRaw = globalQuote["07. latest trading day"].string

        val splitted = latestTradingRaw.split("-")

        val calendar = Calendar.getInstance()

        calendar.set(Calendar.YEAR, splitted[0].toInt())
        calendar.set(Calendar.MONTH, splitted[1].toInt())
        calendar.set(Calendar.DAY_OF_MONTH, splitted[2].toInt())

        return OffsetDateTime.ofInstant(calendar.toInstant(), ZoneId.systemDefault())
    }

    val previousClose = globalQuote["08. previous close"].string.toDouble()
    val change = globalQuote["09. change"].string.toDouble()
    val changePercent = globalQuote["10. change percent"].string.replace("%", "").toDouble()
}

class Config(val consumerKey: String,
             val consumerSecret: String,
             val accessToken: String,
             val accessSecret: String,
             val apiKey: String,
             val symbol: String)

