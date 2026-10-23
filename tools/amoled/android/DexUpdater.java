import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.rewriter.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.File;
import java.util.*;

/** Replaces only the three existing JNI bridge methods; all other code is retained. */
public final class DexUpdater {
  private static final String BRIDGE = "Ldev/cobalt/coat/BaseStarboardBridge;";
  private static final String HELPER = "Ldev/cobalt/updates/ForkUpdater;";
  private static final String CONTEXT = "Landroid/content/Context;";
  private static final String ACTIVITY = "Landroid/app/Activity;";
  private static final String STRING = "Ljava/lang/String;";
  private static final String HOLDER = "Ldev/cobalt/util/Holder;";
  private static int rewritten;

  public static void main(String[] args) throws Exception {
    DexFile input = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.getDefault());
    ClassDef bridge = null;
    for (ClassDef type : input.getClasses()) if (type.getType().equals(BRIDGE)) bridge = type;
    if (bridge == null) {
      if (args.length > 2 && args[2].equals("--optional")) return;
      throw new IllegalArgumentException("JNI bridge not found");
    }
    Set<String> fields = new HashSet<>();
    for (Field field : bridge.getFields()) fields.add(field.getName() + ":" + field.getType());
    if (!fields.contains("mAppContext:" + CONTEXT) || !fields.contains("mActivityHolder:" + HOLDER))
      throw new IllegalArgumentException("JNI bridge fields changed; review the updater patch");
    boolean provider = false, holder = false;
    for (ClassDef type : input.getClasses()) for (Method method : type.getMethods()) {
      if (type.getType().equals("Landroidx/core/content/FileProvider;") && method.getName().equals("getUriForFile")
          && method.getParameterTypes().toString().equals("[" + CONTEXT + ", " + STRING + ", Ljava/io/File;]")) provider = true;
      if (type.getType().equals(HOLDER) && method.getName().equals("get") && method.getParameterTypes().isEmpty()
          && method.getReturnType().equals("Ljava/lang/Object;")) holder = true;
    }
    for (Method method : bridge.getMethods()) if (method.getImplementation() != null)
      for (com.android.tools.smali.dexlib2.iface.instruction.Instruction instruction : method.getImplementation().getInstructions()) {
        if (instruction instanceof com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction) {
          Reference reference = ((com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction) instruction).getReference();
          if (reference instanceof MethodReference) {
            MethodReference target = (MethodReference) reference;
            if (target.getDefiningClass().equals("Landroidx/core/content/FileProvider;") && target.getName().equals("getUriForFile")
                && target.getParameterTypes().toString().equals("[" + CONTEXT + ", " + STRING + ", Ljava/io/File;]")) provider = true;
          }
        }
      }
    if (!provider) throw new IllegalArgumentException("The existing update FileProvider bridge changed");
    if (!holder) throw new IllegalArgumentException("Activity holder changed");
    DexRewriter rewriter = new DexRewriter(new RewriterModule() {
      @Override public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
        return method -> {
          if (!method.getDefiningClass().equals(BRIDGE)) return method;
          String name = method.getName();
          if (!(name.equals("installAppFromURL") || name.equals("getVersion") || name.equals("getArchitecture"))) return method;
          boolean install = name.equals("installAppFromURL");
          if (method.getImplementation() == null || (install
              ? !method.getParameterTypes().toString().equals("[" + STRING + "]") || !method.getReturnType().equals("Z")
              : !method.getParameterTypes().isEmpty() || !method.getReturnType().equals(STRING)))
            throw new IllegalArgumentException("JNI method signature changed: " + name);
          rewritten++;
          java.util.List<com.android.tools.smali.dexlib2.iface.instruction.Instruction> code = new ArrayList<>();
          code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, install ? 3 : 1,
              new ImmutableFieldReference(BRIDGE, "mAppContext", CONTEXT)));
          if (install) {
            code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 3,
                new ImmutableFieldReference(BRIDGE, "mActivityHolder", HOLDER)));
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0,
                new ImmutableMethodReference(HOLDER, "get", Collections.emptyList(), "Ljava/lang/Object;")));
            code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1));
            code.add(new ImmutableInstruction21c(Opcode.CHECK_CAST, 1, new ImmutableTypeReference(ACTIVITY)));
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 0, 1, 4, 0, 0,
                new ImmutableMethodReference(HELPER, "start", Arrays.asList(CONTEXT, ACTIVITY, STRING), "Z")));
            code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0));
            code.add(new ImmutableInstruction11x(Opcode.RETURN, 0));
          } else {
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                new ImmutableMethodReference(HELPER, name, Arrays.asList(CONTEXT), STRING)));
            code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
            code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
          }
          return new ImmutableMethod(method.getDefiningClass(), name, method.getParameters(), method.getReturnType(),
              method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
              new ImmutableMethodImplementation(install ? 5 : 2, code, Collections.emptyList(), Collections.emptyList()));
        };
      }
    });
    DexPool.writeTo(args[1], rewriter.getDexFileRewriter().rewrite(input));
    if (rewritten != 3) throw new IllegalStateException("Expected exactly three bridge method replacements, got " + rewritten);
    DexFile output = DexFileFactory.loadDexFile(new File(args[1]), Opcodes.getDefault());
    if (input.getClasses().size() != output.getClasses().size()) throw new IllegalStateException("Unexpected dex class changes");
    System.out.println("Verified three updater bridge replacements; preserved " + output.getClasses().size() + " classes.");
  }
}
