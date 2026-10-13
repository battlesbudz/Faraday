import java.io.File;
import java.lang.reflect.*;
import java.util.*;

public class AssetMergeRepro {
  public static void main(String[] args) throws Exception {
    File source = new File(args[0]), destination = new File(args[1]);
    Class<?> itemClass = Class.forName("com.android.ide.common.resources.AssetItem");
    Class<?> assetFileClass = Class.forName("com.android.ide.common.resources.AssetFile");
    Class<?> parameterClass = Class.forName("com.android.ide.common.resources.MergedAssetWriter$AssetWorkParameters");
    Class<?> actionClass = Class.forName("com.android.ide.common.resources.MergedAssetWriter$AssetWorkAction");
    Method create = itemClass.getDeclaredMethod("create", File.class, File.class); create.setAccessible(true);
    Constructor<?> assetFile = assetFileClass.getDeclaredConstructor(File.class, itemClass); assetFile.setAccessible(true);
    Constructor<?> parameters = parameterClass.getDeclaredConstructor(List.class, File.class); parameters.setAccessible(true);
    ArrayList<Object> items = new ArrayList<>();
    for (File file : Objects.requireNonNull(source.listFiles())) {
      if (!file.isFile()) continue;
      Object item = create.invoke(null, source, file);
      assetFile.newInstance(file, item);
      items.add(item);
    }
    Runnable action = (Runnable) actionClass.getConstructor(parameterClass).newInstance(parameters.newInstance(items, destination));
    action.run();
    for (File file : Objects.requireNonNull(destination.listFiles())) System.out.println(file.getName() + " " + file.length());
  }
}
