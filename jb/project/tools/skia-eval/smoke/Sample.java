import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File;
public class Sample { public static void main(String[] a) throws Exception {
  BufferedImage m = ImageIO.read(new File(a[0])), s = ImageIO.read(new File(a[1]));
  int[][] pts = {{120,40},{121,40},{170,10},{170,11},{34,154},{118,178},{200,200},{250,200},{30,100},{30,101},{30,102},{300,40},{250,15},{560,50},{300,100},{190,180},{191,180},{192,180}};
  String[] names = {"drawRect left edge","inside rect","drawRect top edge","below top edge","image circle","scaled image circle","alpha rect","alpha rect 2","line area","line area","line area","AA rrect fill","rrect corner","radial center","stroke rrect","text","text","text"};
  for (int i=0;i<pts.length;i++){int x=pts[i][0],y=pts[i][1]; System.out.printf("%-20s (%3d,%3d) metal=%08x skia=%08x%n", names[i], x, y, m.getRGB(x,y), s.getRGB(x,y));}
}}
