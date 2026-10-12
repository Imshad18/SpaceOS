import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Renders the Space OS launcher mark: a ringed world under a reticle, in the
 * same plasma-on-void palette the OS uses everywhere else.
 */
public class MakeIcon {

    static final Color VOID   = new Color(0x00, 0x05, 0x09);
    static final Color VOID2  = new Color(0x03, 0x10, 0x1A);
    static final Color PLASMA = new Color(0x35, 0xE8, 0xFF);
    static final Color ION    = new Color(0x6B, 0xFF, 0x9E);
    static final Color EDGE   = new Color(0x14, 0x48, 0x5C);

    public static void main(String[] args) throws Exception {
        String outRoot = args[0];
        int[] sizes = {48, 72, 96, 144, 192};
        String[] dirs = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi",
                         "mipmap-xxhdpi", "mipmap-xxxhdpi"};
        for (int i = 0; i < sizes.length; i++) {
            File dir = new File(outRoot, dirs[i]);
            dir.mkdirs();
            BufferedImage img = render(sizes[i]);
            ImageIO.write(img, "png", new File(dir, "ic_launcher.png"));
            System.out.println("  " + dirs[i] + "/ic_launcher.png  " + sizes[i] + "px");
        }
    }

    static BufferedImage render(int S) {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        float f = S / 192f;          // design at 192, scale down
        float cx = S / 2f, cy = S / 2f;

        // hull background: rounded square, deep void with a subtle core glow
        Shape hull = new RoundRectangle2D.Float(0, 0, S, S, S * 0.22f, S * 0.22f);
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(cx, cy), S * 0.62f,
                new float[]{0f, 1f},
                new Color[]{VOID2, VOID}));
        g.fill(hull);

        g.setClip(hull);

        // starfield
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 26; i++) {
            float x = rnd.nextFloat() * S, y = rnd.nextFloat() * S;
            float a = 0.20f + rnd.nextFloat() * 0.5f;
            g.setColor(new Color(1f, 1f, 1f, a));
            float r = rnd.nextFloat() > 0.85f ? 1.6f * f : 0.9f * f;
            g.fill(new Ellipse2D.Float(x, y, r, r));
        }

        // outer reticle ring
        g.setStroke(new BasicStroke(1.6f * f));
        g.setColor(new Color(EDGE.getRed(), EDGE.getGreen(), EDGE.getBlue(), 200));
        float rr = S * 0.42f;
        g.draw(new Ellipse2D.Float(cx - rr, cy - rr, rr * 2, rr * 2));

        // reticle ticks
        g.setStroke(new BasicStroke(2.2f * f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(PLASMA);
        for (int i = 0; i < 4; i++) {
            double a0 = Math.toRadians(i * 90 - 16);
            double a1 = Math.toRadians(i * 90 + 16);
            g.draw(new Arc2D.Double(cx - rr, cy - rr, rr * 2, rr * 2,
                    -Math.toDegrees(a0), -(Math.toDegrees(a1) - Math.toDegrees(a0)),
                    Arc2D.OPEN));
        }

        // the world
        float pr = S * 0.235f;
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(cx - pr * 0.35f, cy - pr * 0.4f), pr * 1.9f,
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0x2A, 0xC8, 0xE8), new Color(0x0E, 0x5A, 0x74),
                            new Color(0x05, 0x1C, 0x28)}));
        g.fill(new Ellipse2D.Float(cx - pr, cy - pr, pr * 2, pr * 2));

        // terminator arc on the world
        g.setStroke(new BasicStroke(1.4f * f));
        g.setColor(new Color(PLASMA.getRed(), PLASMA.getGreen(), PLASMA.getBlue(), 150));
        g.draw(new Ellipse2D.Float(cx - pr, cy - pr, pr * 2, pr * 2));
        g.setClip(new Ellipse2D.Float(cx - pr, cy - pr, pr * 2, pr * 2));
        g.setColor(new Color(PLASMA.getRed(), PLASMA.getGreen(), PLASMA.getBlue(), 70));
        for (int i = -2; i <= 2; i++) {
            float y = cy + i * pr * 0.42f;
            g.draw(new Arc2D.Float(cx - pr * 1.4f, y - pr * 0.5f, pr * 2.8f, pr,
                    200, 140, Arc2D.OPEN));
        }
        g.setClip(hull);

        // the ring, tilted
        AffineTransform old = g.getTransform();
        g.translate(cx, cy);
        g.rotate(Math.toRadians(-22));
        g.scale(1f, 0.30f);
        g.setStroke(new BasicStroke(3.4f * f));
        g.setColor(ION);
        float ringR = S * 0.375f;
        g.draw(new Ellipse2D.Float(-ringR, -ringR, ringR * 2, ringR * 2));
        g.setStroke(new BasicStroke(1.2f * f));
        g.setColor(new Color(ION.getRed(), ION.getGreen(), ION.getBlue(), 140));
        float ringR2 = S * 0.315f;
        g.draw(new Ellipse2D.Float(-ringR2, -ringR2, ringR2 * 2, ringR2 * 2));
        g.setTransform(old);

        // crosshair ticks at the cardinal points
        g.setStroke(new BasicStroke(2f * f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(PLASMA);
        float t0 = S * 0.44f, t1 = S * 0.485f;
        g.draw(new Line2D.Float(cx, cy - t0, cx, cy - t1));
        g.draw(new Line2D.Float(cx, cy + t0, cx, cy + t1));
        g.draw(new Line2D.Float(cx - t0, cy, cx - t1, cy));
        g.draw(new Line2D.Float(cx + t0, cy, cx + t1, cy));

        g.dispose();
        return img;
    }
}
