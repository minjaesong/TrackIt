package net.torvald.trackit

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import net.torvald.terrarum.langpack.Lang
import net.torvald.terrarumsansbitmap.gdx.GameFontBase

object TaskMain : Screen {

    //private lateinit var targetTex: Texture
    private lateinit var playerTex: Texture

    private lateinit var backGrassTile: Texture
    private lateinit var roadTile: Texture

    private lateinit var batch: SpriteBatch
    private lateinit var shapeRenderer: ShapeRenderer


    val targetPos = Gdx.graphics.width / 2f
    var playerPos = Gdx.graphics.width / 2f

    private val runtime = 30f // seconds


    private val displaceMax = 180f
    private val minimalDisplacement = 120f
    private val disturbancePointCount = 4
    private var disturbancePoints = FloatArray(disturbancePointCount + 4, {
        val rnd = Math.random().toFloat() * 2f - 1f // [-1f..1f)
        rnd * displaceMax + (if (rnd > 0f) minimalDisplacement else -minimalDisplacement)
    })
    // cosine interpolation, ensure 2 negs and 2 pos
    // displace PLAYER by reference-value-of-current-frame minus reference-value-of-prev-frame
    private val disturbanceInterval = runtime / disturbancePointCount

    private var prevDisturbance = 0f // used for doing discrete differentiation


    private val dataPointCount = 200

    private val pollingTime = runtime / dataPointCount

    private val dataPoints = ArrayList<ControlDataPoints>()

    private var runtimeCounter = 0f
    private var dataCaptureCounter = 0f

    private var startGame = false

    lateinit var font: GameFontBase


    private lateinit var backgroundTilesQuad: Mesh


    private lateinit var scrollShader: ShaderProgram


    enum class TaskMode {
        MODE_INIT, MODE_PLAYING, MODE_RESULT
    }

    private var currentMode = TaskMode.MODE_INIT

    init {

        resetGame()

    }

    /**
     * Get random value relative to previous value.
     *
     * Will flip the sign of prev value based on predefined probability.
     *
     * @return random value between -1 to 1
     */
    private fun rnd(valBefore: Float): Float {
        val flipProbability = 1.0//0.7
        val flipRnd = Math.random()
        val rnd = Math.random().toFloat() // [0..1]

        if (valBefore == 0f) return rnd * 2f - 1f // [-1..1]
        
        if (flipRnd < flipProbability) {
            return if (valBefore > 0f) -rnd else rnd // flip sign
        }
        else {
            return if (valBefore > 0f) rnd else -rnd // don't flip sign
        }
    }

    private infix fun Float.addOffset(amplitude: Float): Float =
            if (this < 0.0) this - amplitude
            else this + amplitude

    private fun resetGame() {
        disturbancePoints = FloatArray(disturbancePointCount + 4, { 0f })
        for (it in 2 until disturbancePoints.size) {
            if (it == 0) {
                disturbancePoints[0] = 0f //(rnd() * displaceMax) addOffset minimalDisplacement
            }
            else {
                disturbancePoints[it] = disturbancePoints[it - 1] + (rnd(disturbancePoints[it - 1]) * (displaceMax - minimalDisplacement) addOffset minimalDisplacement)
            }
        }
        dataPoints.clear()
        runtimeCounter = 0f
        dataCaptureCounter = 0f
        startGame = false
        currentMode = TaskMode.MODE_INIT
    }

    override fun hide() {
        dispose()
    }

    override fun show() {
        batch = SpriteBatch()
        shapeRenderer = ShapeRenderer()

        Gdx.input.inputProcessor = TrackItInputListener()

        playerTex = Texture(Gdx.files.internal("assets/player.tga"))

        font = GameFontBase("assets/fonts")

        backGrassTile = Texture(Gdx.files.internal("assets/background_tile.tga"))

        roadTile = Texture(Gdx.files.internal("assets/target.tga"))


        backgroundTilesQuad = Mesh(
                true, 4, 6,
                VertexAttribute.Position(),
                VertexAttribute.ColorUnpacked(),
                VertexAttribute.TexCoords(0)
        )

        val backTilesW = Gdx.graphics.width.toFloat() /  backGrassTile.width
        val backTilesH = Gdx.graphics.height.toFloat() / backGrassTile.height

        backgroundTilesQuad.setVertices(floatArrayOf(
                0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f, backTilesH,
                Gdx.graphics.width.toFloat(), 0f, 0f, 1f, 1f, 1f, 1f, backTilesW, backTilesH,
                Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat(), 0f, 1f, 1f, 1f, 1f, backTilesW, 0f,
                0f, Gdx.graphics.height.toFloat(), 0f, 1f, 1f, 1f, 1f, 0f, 0f
        ))
        backgroundTilesQuad.setIndices(shortArrayOf(0, 1, 2, 2, 3, 0))


        scrollShader = ShaderProgram(Gdx.files.internal("scroll.vert"), Gdx.files.internal("scroll.frag"))

        if (!scrollShader.isCompiled) {
            Gdx.app.log("scrollShader", scrollShader.log)
            System.exit(1)
        }
    }

    private var rmseCalculated = false
    private var rmse = 0f

    override fun render(delta: Float) {
        if (startGame) {
            Gdx.gl.glClearColor(.933f, .933f, .933f, 1f)
        }
        else {
            Gdx.gl.glClearColor(.235f, .235f, .235f, 1f)
        }
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        Gdx.graphics.setTitle("Track It! — FPS: ${Gdx.graphics.framesPerSecond}")


        // TODO start when START is pressed, which is placed at the centre of the screen so that players would be able to align

        if (!startGame) {
            batch.inUse {
                batch.color = Color.WHITE
                val textWidth = font.getWidth(Lang["MENU_LABEL_PLAY"])
                font.draw(batch, Lang["MENU_LABEL_PLAY"], (Gdx.graphics.width - textWidth) / 2f, Gdx.graphics.height / 2f)
            }
        }


        // TASK MODE
        if (runtimeCounter < runtime && startGame) {

            val scrollIntensity = 40f

            currentMode = TaskMode.MODE_PLAYING


            // DRAW

            // draw and scroll background
            backGrassTile.bind(0)
            scrollShader.begin()
            scrollShader.setUniformf("scroll", 0f, (scrollIntensity * runtimeCounter) % 1f)
            scrollShader.setUniformi("u_texture", 0)
            scrollShader.setUniformMatrix("u_projTrans", batch.projectionMatrix)
            backgroundTilesQuad.render(scrollShader, GL20.GL_TRIANGLES)
            scrollShader.end()


            val tilesNeededToFillScreen = (Gdx.graphics.height / roadTile.height) + 1
            batch.inUse {
                val scrollOffset = -(runtimeCounter.times(scrollIntensity * backGrassTile.height) % roadTile.height)
                for (i in 0..tilesNeededToFillScreen) {
                    batch.draw(roadTile, (Gdx.graphics.width - roadTile.width) / 2f, scrollOffset + roadTile.height * i)
                }
            }



            batch.inUse {
                // draw objects
                batch.draw(playerTex, (playerPos - playerTex.width / 2), (Gdx.graphics.height - playerTex.height) / 2f)
            }


            // UPDATE

            val newDisturbance = interpolateHermite(
                    (runtimeCounter % disturbanceInterval) / disturbanceInterval,
                    disturbancePoints[(runtimeCounter / disturbanceInterval).toInt()],
                    disturbancePoints[minOf((runtimeCounter / disturbanceInterval).toInt() + 1, disturbancePoints.lastIndex)],
                    disturbancePoints[minOf((runtimeCounter / disturbanceInterval).toInt() + 2, disturbancePoints.lastIndex)],
                    disturbancePoints[minOf((runtimeCounter / disturbanceInterval).toInt() + 3, disturbancePoints.lastIndex)]
            )
            playerPos += newDisturbance - prevDisturbance

            prevDisturbance = newDisturbance


            if (dataCaptureCounter > pollingTime) {
                dataCaptureCounter -= pollingTime
                dataPoints.add(ControlDataPoints(
                        newDisturbance + targetPos,
                        playerPos,
                        targetPos
                ))
            }


            runtimeCounter += delta
            dataCaptureCounter += delta
        }
        // RESULT MODE
        else if (startGame) {

            currentMode = TaskMode.MODE_RESULT


            val dataPointCentre = Gdx.graphics.height / 2f
            val pointMargin = 3f
            val pointSize = 2f
            val leftMargin = 8f

            rmseCalculated = false

            if (!rmseCalculated) {
                var errorSum = 0.0
                dataPoints.forEach {
                    val normMovement = it.movement - (Gdx.graphics.width / 2.0)

                    errorSum += Math.pow(normMovement, 2.0)
                }

                rmse = Math.sqrt(errorSum / dataPoints.size).toFloat()

                rmseCalculated = true
            }


            // draw graphs
            shapeRenderer.inUse {

                shapeRenderer.color = Color(0x404040ff)
                // assumes fixed reference position
                shapeRenderer.rect(leftMargin,  dataPointCentre, pointMargin * dataPoints.size, 1f)


                dataPoints.forEachIndexed { index, controlDataPoints ->
                    val normMovement = controlDataPoints.movement - (Gdx.graphics.width / 2f)
                    val normDisturbance = controlDataPoints.disturbance - (Gdx.graphics.width / 2f)

                    shapeRenderer.color = Color(0x404040ff)
                    shapeRenderer.rect(leftMargin + index * pointMargin, normDisturbance + dataPointCentre, pointSize, pointSize)

                    shapeRenderer.color = Color.RED
                    shapeRenderer.rect(leftMargin + index * pointMargin, normMovement + dataPointCentre, pointSize, pointSize)

                    shapeRenderer.color = Color(0x00cc50ff)
                    shapeRenderer.rect(leftMargin + index * pointMargin, -(normDisturbance - normMovement) + dataPointCentre, pointSize, pointSize)

                }
            }


            batch.inUse {
                batch.color = Color(0x00ccffff)
                font.draw(batch, "RMS Error: $rmse", 10f, Gdx.graphics.height - font.lineHeight - 10f)

                batch.color = Color(0x00ff55ff)

                val fontWidth = font.getWidth(Lang["MENU_LABEL_RETURN_MAIN"]).ushr(1).shl(1) // ensure even-ness (as in even number)
                font.draw(batch, Lang["MENU_LABEL_RETURN_MAIN"], Gdx.graphics.width.minus(fontWidth) / 2f, 10f)
            }

        }
    }

    override fun pause() {
    }

    override fun resume() {
    }

    override fun resize(p0: Int, p1: Int) {
    }

    override fun dispose() {
        playerTex.dispose()

        backGrassTile.dispose()
        roadTile.dispose()
    }


    /*private fun interpolateCosine(scale: Float, startValue: Float, endValue: Float): Float {
        val ft = scale * Math.PI
        val f = (1 - Math.cos(ft)) * 0.5f

        return (startValue * (1 - f) + endValue * f).toFloat()
    }*/

    private fun interpolateHermite(scale: Float, p0: Float, p1: Float, p2: Float, p3: Float, tension: Float = 1f, bias: Float = 0f): Float {
        val mu2 = scale * scale
        val mu3 = mu2 * scale

        var m0 = (p1 - p0) * (1f + bias) * (1f - tension) / 2f
        m0 += (p2 - p1) * (1f + bias) * (1f - tension) / 2f
        var m1 = (p2 - p1) * (1f + bias) * (1f - tension) / 2f
        m1 += (p3 - p2) * (1f + bias) * (1f - tension) / 2f

        val a0 = 2 * mu3 - 3 * mu2 + 1
        val a1 = mu3 - 2 * mu2 + scale
        val a2 = mu3 - mu2
        val a3 = -2 * mu3 + 3 * mu2

        return a0 * p1 + a1 * m0 + a2 * m1 + a3 * p2
    }



    class TrackItInputListener : InputProcessor {

        private var oldMouseX = -100

        override fun touchUp(p0: Int, p1: Int, p2: Int, p3: Int): Boolean {
            return false
        }

        override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
            if (currentMode == TaskMode.MODE_PLAYING) {
                if (oldMouseX == -100) oldMouseX = screenX // init, -100 is an placeholder value

                playerPos -= (oldMouseX - screenX).toFloat()

                oldMouseX = screenX
            }

            return true
        }

        override fun keyTyped(p0: Char): Boolean {
            return false
        }

        override fun scrolled(p0: Int): Boolean {
            return false
        }

        override fun keyUp(p0: Int): Boolean {
            return false
        }

        override fun touchDragged(x: Int, y: Int, pointer: Int): Boolean {
            if (currentMode == TaskMode.MODE_PLAYING) {
                return mouseMoved(x, y)
            }

            return true
        }

        override fun keyDown(p0: Int): Boolean {
            return false
        }

        override fun touchDown(x: Int, y: Int, pointer: Int, button: Int): Boolean {
            if (currentMode == TaskMode.MODE_INIT) startGame = true

            // return to main menu
            else if (currentMode == TaskMode.MODE_RESULT && y >= Gdx.graphics.height - 40) {
                resetGame()
            }

            return true
        }
    }
}


inline fun SpriteBatch.inUse(action: (SpriteBatch) -> Unit) {
    this.begin()
    action.invoke(this)
    this.end()
}

inline fun ShapeRenderer.inUse(action: (ShapeRenderer) -> Unit) {
    this.begin(ShapeRenderer.ShapeType.Filled)
    action.invoke(this)
    this.end()
}

data class ControlDataPoints(val disturbance: Float, val movement: Float, val referenceValue: Float)