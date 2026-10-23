import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.builder.*;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.File;
import java.util.*;

/** Preserve decoder logic/try blocks; redirect two framework calls and observe format changes. */
public final class DexVideoPatcher {
  private static final String CODEC = "Landroid/media/MediaCodec;";
  private static final String FORMAT = "Landroid/media/MediaFormat;";
  private static final String HELPER = "Ldev/cobalt/updates/VideoUpscaler;";
  private static final String BRIDGE = "Ldev/cobalt/media/MediaCodecBridge;";
  private static int configured, released, notified;

  private static Method rewrite(Method method) {
    MethodImplementation original = method.getImplementation();
    if (original == null || !method.getDefiningClass().startsWith("Ldev/cobalt/media/MediaCodecBridge")) return method;
    List<Instruction> instructions = new ArrayList<>();
    for (Instruction instruction : original.getInstructions()) {
      if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference) {
        MethodReference target = (MethodReference) ((ReferenceInstruction) instruction).getReference();
        boolean configure = method.getDefiningClass().equals(BRIDGE) && method.getName().equals("configureVideo")
            && target.getDefiningClass().equals(CODEC) && target.getName().equals("configure")
            && target.getParameterTypes().toString().equals("[" + FORMAT + ", Landroid/view/Surface;, Landroid/media/MediaCrypto;, I]");
        boolean release = method.getDefiningClass().equals(BRIDGE) && method.getName().equals("release")
            && target.getDefiningClass().equals(CODEC) && target.getName().equals("release") && target.getParameterTypes().isEmpty();
        if (configure || release) {
          List<String> parameters = new ArrayList<>(); parameters.add(CODEC);
          for (CharSequence type : target.getParameterTypes()) parameters.add(type.toString());
          ImmutableMethodReference hook = new ImmutableMethodReference(HELPER, configure ? "configure" : "release", parameters, "V");
          if (instruction instanceof Instruction35c) {
            Instruction35c call = (Instruction35c) instruction;
            instruction = new ImmutableInstruction35c(Opcode.INVOKE_STATIC, call.getRegisterCount(),
                call.getRegisterC(), call.getRegisterD(), call.getRegisterE(), call.getRegisterF(), call.getRegisterG(), hook);
          } else if (instruction instanceof Instruction3rc) {
            Instruction3rc call = (Instruction3rc) instruction;
            instruction = new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, call.getStartRegister(), call.getRegisterCount(), hook);
          } else throw new IllegalStateException("Unexpected codec invocation format");
          if (configure) configured++; else released++;
        }
      }
      instructions.add(instruction);
    }
    MethodImplementation implementation = new ImmutableMethodImplementation(original.getRegisterCount(), instructions,
        original.getTryBlocks(), original.getDebugItems());
    if (method.getName().equals("onOutputFormatChanged") && method.getParameterTypes().toString().equals("[" + CODEC + ", " + FORMAT + "]")) {
      MutableMethodImplementation mutable = new MutableMethodImplementation(implementation);
      mutable.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
          original.getRegisterCount() - 2, 2,
          new ImmutableMethodReference(HELPER, "outputFormat", Arrays.asList(CODEC, FORMAT), "V")));
      implementation = mutable; notified++;
    }
    return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(), method.getReturnType(),
        method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), implementation);
  }

  public static void main(String[] args) throws Exception {
    DexFile input = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.getDefault());
    List<ClassDef> classes = new ArrayList<>(); boolean surface = false;
    for (ClassDef type : input.getClasses()) {
      List<Method> methods = new ArrayList<>();
      for (Method method : type.getMethods()) {
        if (type.getType().equals("Ldev/cobalt/media/VideoSurfaceView;") && method.getName().equals("getCurrentSurface")
            && method.getParameterTypes().isEmpty() && method.getReturnType().equals("Landroid/view/Surface;")) surface = true;
        methods.add(rewrite(method));
      }
      classes.add(new ImmutableClassDef(type.getType(), type.getAccessFlags(), type.getSuperclass(), type.getInterfaces(),
          type.getSourceFile(), type.getAnnotations(), type.getFields(), methods));
    }
    if (configured != 1 || released != 1 || notified != 1 || !surface)
      throw new IllegalStateException("Decoder surface API changed; expected 1 configure/release/format and display surface; got "
          + configured + "/" + released + "/" + notified + "/" + surface);
    DexPool.writeTo(args[1], new ImmutableDexFile(input.getOpcodes(), classes));
    DexFile output = DexFileFactory.loadDexFile(new File(args[1]), Opcodes.getDefault());
    if (input.getClasses().size() != output.getClasses().size()) throw new IllegalStateException("Decoder classes changed");
    System.out.println("Verified SGSR1 configure/release/format hooks; preserved " + classes.size() + " classes.");
  }
}
