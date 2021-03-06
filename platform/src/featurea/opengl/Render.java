package featurea.opengl;

import featurea.app.Camera;
import featurea.app.Context;
import featurea.app.MediaPlayer;
import featurea.graphics.Glyph;
import featurea.util.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

import static featurea.app.Context.gl;
import static featurea.opengl.OpenGL.GL_FLOAT;
import static featurea.opengl.OpenGL.GL_TRIANGLES;
import static featurea.opengl.OpenGLManager.*;

public class Render {

  public final MediaPlayer mediaPlayer;
  public boolean isReleaseTexturesOnPause = true;
  public featurea.graphics.Window window;
  public final Size size = new Size();
  public Zoom zoom = new Zoom();
  public final TexturePacker texturePacker = new TexturePacker();
  public final DefaultTextureManager defaultTextureManager = new DefaultTextureManager();
  public boolean isScreenMode;
  public boolean isOutline;

  public Render(MediaPlayer mediaPlayer) {
    this.mediaPlayer = mediaPlayer;
    setBatchSize(1_000);
  }

  private final Line2d line = new Line2d();
  private final Rectangle2d rectangle = new Rectangle2d();
  private final Polygon2d polygon = new Polygon2d();
  public TexturePart part;

  public static FloatBuffer createFloatBuffer(int size) {
    return ByteBuffer.allocateDirect(size << 2).order(ByteOrder.nativeOrder()).asFloatBuffer();
  }

  public void drawLine(double x1, double y1, double x2, double y2, Color color) {
    unbind();
    line.setValue(x1, y1, x2, y2);
    line.setColor(color);
    line.render();
  }

  public void drawRectangle(double x1, double y1, double x2, double y2, Color color) {
    unbind();
    drawLine(x1, y1, x2, y1, color);
    drawLine(x2, y1, x2, y2, color);
    drawLine(x2, y2, x1, y2, color);
    drawLine(x1, y2, x1, y1, color);
  }

  public void fillRectangle(double x1, double y1, double x2, double y2, Color color) {
    unbind();
    rectangle.render(x1, y1, x2, y2, 0, color);
  }

  private final double[] circlePoints = new double[360 * 2];

  public void drawCircle(double ox, double oy, double radiusX, double radiusY, Color color, Angle rotationAngle) {
    unbind();
    for (int i = 0; i < circlePoints.length; i += 2) {
      int angle = i / 2;
      circlePoints[i] = (ox + radiusX * MathUtil.cosDeg(angle));
      circlePoints[i + 1] = (oy + radiusY * MathUtil.sinDeg(angle));
    }
    for (int i = 0; i < circlePoints.length; i += 2) {
      double x1 = circlePoints[i];
      double y1 = circlePoints[i + 1];
      double x2 = circlePoints[(i + 2) % circlePoints.length];
      double y2 = circlePoints[(i + 3) % circlePoints.length];
      double[] x1y1 = Vector.rotate(x1, y1, ox, oy, rotationAngle);
      x1 = x1y1[0];
      y1 = x1y1[1];
      double[] x2y2 = Vector.rotate(x2, y2, ox, oy, rotationAngle);
      x2 = x2y2[0];
      y2 = x2y2[1];
      drawLine(x1, y1, x2, y2, color);
    }
  }

  public void fillShape(double[] points, Color color) {
    unbind();
    polygon.render(points, color);
  }

  public void drawTexture(String file, double x1, double y1, double x2, double y2, Angle angle,
                          double ox, double oy, Color color, boolean isFlipX, boolean isFlipY, List<Shader> shaders) {
    if (containsPessimistically(x1, y1, x2, y2)) {
      if (file != null) {
        Texture texture = mediaPlayer.getResources().getTexture(file);
        if (texture != null) {
          texture.draw(x1, y1, x2, y2, angle, ox, oy, color, isFlipX, isFlipY, shaders);
        } else {
          if (!mediaPlayer.isProduction()) {
            mediaPlayer.loader.load(file);
          } else {
            System.err.println("Texture not load: " + file);
          }
        }
      }
      mediaPlayer.performance.drawTextureCount++;
    } else {
      // no op
    }
  }

  public void unbind() {
    if (part != null) {
      flush();
      part = null;
      gl.unbind();
    }
  }

  public void bind(TexturePart part) {
    this.part = part;
    gl.bind(part.id);
  }

  private int batchSize;
  private int count;
  private FloatBuffer vertexPointer;
  private FloatBuffer colorPointer;
  private FloatBuffer texCoordPointer;

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
    vertexPointer = createFloatBuffer(2 * 3 * 2 * batchSize);
    colorPointer = createFloatBuffer(4 * 3 * 2 * batchSize);
    texCoordPointer = createFloatBuffer(2 * 3 * 2 * batchSize);
  }

  private void flip() {
    texCoordPointer.flip();
    vertexPointer.flip();
    colorPointer.flip();
  }

  public void add(FloatBuffer texCoordPointer, FloatBuffer vertexPointer, FloatBuffer colorPointer) {
    this.texCoordPointer.put(texCoordPointer);
    this.vertexPointer.put(vertexPointer);
    this.colorPointer.put(colorPointer);
    count++;
  }

  public void flush() {
    flip();
    gl.glPushMatrix();
    gl.glVertexPointer(VERTEX_POINTER_COUNT, GL_FLOAT, 0, vertexPointer);
    gl.glColorPointer(COLOR_POINTER_COUNT, GL_FLOAT, 0, colorPointer);
    gl.glTexCoordPointer(TEXTURE_COORD_POINTER_COUNT, GL_FLOAT, 0, texCoordPointer);
    gl.glDrawArrays(GL_TRIANGLES, 0, COUNT_OF_VERTICES_FOR_DRAWING_TWO_TRIANGLES * count);
    gl.glPopMatrix();
    clear();
  }

  private void clear() {
    this.texCoordPointer.clear();
    this.vertexPointer.clear();
    this.colorPointer.clear();
    count = 0;
  }

  public boolean isFull() {
    return count == batchSize;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public TextureManager getTextureManager() {
    if (mediaPlayer.isProduction()) {
      return texturePacker;
    } else {
      return defaultTextureManager;
    }
  }

  public void release(String file) {
    getTextureManager().release(file);
  }

  /**
   * @return are all packs loaded
   */
  public void load(String file) throws TextureNotFoundException {
    getTextureManager().load(file);
  }

  public void resize(int width, int height) {
    size.setValue(width, height);
    gl.glViewport(0, 0, width, height);
    gl.glLoadIdentity();
    gl.glOrthof(0, width, height, 0, -1, 1);
  }

  private boolean containsPessimistically(double x1, double y1, double x2, double y2) {
    double ox = (x1 + x2) / 2;
    double oy = (y1 + y2) / 2;
    double max = Math.max(x2 - x1, y2 - y1);
    return ox - max < size.width && ox + max > 0 && oy - max < size.height && oy + max > 0;
  }

  public void cropWithCamera(Camera camera) {
    double x = camera.zoom.x;
    double y = camera.zoom.y;
    cropWithCamera(x, y);
  }

  public void cropWithCamera(double x, double y) {
    if (x != 0) {
      fillRectangle(0, 0, (double) x, (double) size.height, Colors.black);
      fillRectangle((double) (size.width - x), 0, (double) size.width, (double) size.height, Colors.black);
    } else if (y != 0) {
      fillRectangle(0, 0, (double) size.width, (double) y, Colors.black);
      fillRectangle(0, (double) (size.height - y), (double) size.width, (double) size.height, Colors.black);
    }
  }

  public void drawGlyph(Glyph glyph, double x1, double y1, double x2, double y2, Angle angle, double ox, double oy, Color color, boolean isFlipX, boolean isFlipY) {
    Texture texture = mediaPlayer.getResources().getTexture(glyph.font.pngFile);
    if (texture != null) {
      texture.setGlyphToRender(glyph);
      texture.glyphRender.draw(x1, y1, x2, y2, angle, ox, oy, color, isFlipX, isFlipY, null);
    } else {
      if (!mediaPlayer.isProduction()) {
        mediaPlayer.loader.load(glyph.font.pngFile);
      } else {
        System.out.println("Font not load: " + glyph.font.pngFile);
      }
    }
  }

  public void clearBackground(Color background) {
    Context.gl.glClearColor((float) background.r, (float) background.g, (float) background.b, (float) background.a);
    Context.gl.glClear(OpenGL.GL_COLOR_BUFFER_BIT);
  }

  /**/

  public void onCreate() {
    int[] maxTextureSize = new int[1];
    gl.glGetIntegerv(OpenGL.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
    OpenGLManager.MAX_TEXTURE_SIZE = maxTextureSize[0];
  }

  public void onStart() {
    if (isReleaseTexturesOnPause) {
      for (Texture texture : mediaPlayer.getResources().getTextures()) {
        mediaPlayer.loader.load(texture.file);
      }
    }
  }

  public void onStop() {
    if (isReleaseTexturesOnPause) {
      invalidateTextures();
    }
  }

  public void invalidateTextures() {
    for (Texture texture : mediaPlayer.getResources().getTextures()) {
      mediaPlayer.loader.release(texture.file);
    }
  }

  public void onDestroy() {
    /*for (Texture texture : getTextures()) {
      texture.release();
    }*/
    texturePacker.packs.clear();
    defaultTextureManager.textures.clear();
  }

}
