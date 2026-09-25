// Ghidra headless post-script: decompile every function of the current program
// into one C file.
// Usage (via analyzeHeadless): -postScript DecompileAll.java <out.c> [names.tsv]
// names.tsv: "<hex addr>\t<name>" lines; a function is created and named at each
// address first (for entry points only referenced from data, e.g. JNI tables).
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.SourceType;

public class DecompileAll extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length > 1) {
            for (String line : Files.readAllLines(Path.of(args[1]))) {
                String[] p = line.trim().split("\t");
                if (p.length < 2) continue;
                Address a = toAddr(Long.parseLong(p[0], 16));
                new DisassembleCommand(a, null, true).applyTo(currentProgram, monitor);
                Function f = getFunctionAt(a);
                if (f == null) f = createFunction(a, p[1]);
                if (f == null) {
                    // Address was swallowed by a neighbouring function body: split it.
                    Function g = getFunctionContaining(a);
                    if (g != null) {
                        Address ge = g.getEntryPoint();
                        String gn = g.getName();
                        removeFunction(g);
                        f = createFunction(a, p[1]);
                        createFunction(ge, gn);
                    }
                }
                if (f != null) f.setName(p[1], SourceType.USER_DEFINED);
            }
            analyzeChanges(currentProgram);
        }
        DecompInterface ifc = new DecompInterface();
        ifc.openProgram(currentProgram);
        try (PrintWriter w = new PrintWriter(args[0])) {
            for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
                if (monitor.isCancelled()) break;
                DecompileResults r = ifc.decompileFunction(f, 60, monitor);
                w.println("// ==== " + f.getName() + " @ " + f.getEntryPoint());
                if (r.decompileCompleted()) {
                    w.println(r.getDecompiledFunction().getC());
                } else {
                    w.println("// decompile failed: " + r.getErrorMessage());
                }
            }
        }
        ifc.dispose();
    }
}
