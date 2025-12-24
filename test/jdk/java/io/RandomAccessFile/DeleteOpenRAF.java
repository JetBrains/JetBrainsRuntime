/* @test
   @bug https://youtrack.jetbrains.com/issue/JBR-9823
 * @summary Check that an open RandomAccessFile can be deleted on Windows.
 */

import java.io.*;

public class DeleteOpenRAF {
    public static void main(String[] args) throws Exception {
        File f = File.createTempFile("DeleteOpenRAF", null);
        f.deleteOnExit();
        try (RandomAccessFile ignored = new RandomAccessFile(f, "r")) {
            if (!f.delete()) {
                throw new RuntimeException("Cannot delete file");
            }
        }
    }
}
