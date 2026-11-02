import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File;
public class Sample2 { public static void main(String[] a) throws Exception {
  BufferedImage sw = ImageIO.read(new File("skia-offscreen-software.png")), m = ImageIO.read(new File("skia-offscreen-metal.png")), s = ImageIO.read(new File("skia-offscreen-skia.png"));
  int[][] pts = {{34,154},{35,154},{118,178},{119,178},{400,40},{420,40},{460,40},{500,40},{400,95},{440,95},{480,95},{560,50},{545,35},{575,65},{200,180},{300,180},{20,100},{20,101}};
  String[] names = {"img circle","img circle","scaled img","scaled img","GradientPaint","GradientPaint","GradientPaint","GradientPaint","LinearGP","LinearGP","LinearGP","radial center","radial","radial","AA text","AA text","lines","lines"};
  for (int i=0;i<pts.length;i++){int x=pts[i][0],y=pts[i][1]; System.out.printf("%-14s (%3d,%3d) sw=%08x metal=%08x skia=%08x%n", names[i], x, y, sw.getRGB(x,y), m.getRGB(x,y), s.getRGB(x,y));}
}}
