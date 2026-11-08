// Historical defect probe; use a disposable dummy file, never a real credentials file.
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
public class P66ThirdAclProbe {
  public static void main(String[] args) throws Exception {
    Path file = Path.of(args[0]);
    Files.writeString(file, "dummy-only-no-real-credentials");
    var view = Files.getFileAttributeView(file, AclFileAttributeView.class);
    var users = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("BUILTIN\\Users");
    var acl = new ArrayList<>(view.getAcl());
    acl.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(users)
      .setPermissions(AclEntryPermission.READ_DATA).build());
    view.setAcl(acl);
    var method = Class.forName("cn.gulupai.server.identity.cli.DemoSeedService")
      .getDeclaredMethod("restrictToOwner", Path.class);
    method.setAccessible(true);
    method.invoke(null, file);
    System.out.println("POSIX supported=" + (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null));
    System.out.println("BUILTIN Users read survives=" + view.getAcl().stream().anyMatch(e ->
      e.type() == AclEntryType.ALLOW && e.principal().equals(users) && e.permissions().contains(AclEntryPermission.READ_DATA)));
  }
}
