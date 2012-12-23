package agaricus.applysrg;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;

import java.util.ArrayList;
import java.util.List;

public class ApplySrgActionExperimental extends AnAction {
    public Project project;
    public JavaPsiFacade facade;

    public ApplySrgActionExperimental() {
        super("Apply Srg");
    }

    /** Get list of Java files selected by the user
     *
     * @param event
     * @return List of Java files with PSI ready to process
     */
    private List<PsiJavaFile> getSelectedJavaFiles(AnActionEvent event) {
        List<PsiJavaFile> javaFileList = new ArrayList<PsiJavaFile>();
        PsiManager psiManager = PsiManager.getInstance(project);

        // Get selected files
        VirtualFile[] selectedFiles = event.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY);
        System.out.println("Selected "+ selectedFiles.length+" files");
        if (selectedFiles.length == 0) {
            Messages.showMessageDialog(project, "Please select the files you want to transform in the View > Tool Windows > Project view, then try again.", "No selection", Messages.getErrorIcon());
            return null;
        }
        List<VirtualFile> skippedFiles = new ArrayList<VirtualFile>();
        for(VirtualFile file: selectedFiles) {
            System.out.println("- " + file);
            PsiFile psiFile = psiManager.findFile(file);
            if (psiFile == null) {
                // no psi structure for this file
                skippedFiles.add(file);
                continue;
            }

            System.out.println("psiFile: " + psiFile);

            if (!(psiFile instanceof PsiJavaFile)) {
                System.out.println("Skipping non-Java file "+file);
                skippedFiles.add(file);
                // TODO: possibly try to load this file as Java for PSI parsing
                // see http://devnet.jetbrains.net/thread/271253 , once its back up
                continue;
            }

            PsiJavaFile psiJavaFile = (PsiJavaFile)psiFile;

            javaFileList.add(psiJavaFile);
        }

        if (skippedFiles.size() != 0) {

            StringBuilder sb = new StringBuilder("Non-Java files were selected ("+skippedFiles.size()+" of "+selectedFiles.length+"): \n\n");
            for (VirtualFile skippedFile: skippedFiles) {
                sb.append("- " + skippedFile.getPresentableName() + "\n");
            }

            if (skippedFiles.size() == selectedFiles.length) {
                sb.append("\nNo valid Java files in your project were selected. Please select the files you want to process in View > Tool Windows > Project and try again.");
                Messages.showMessageDialog(project, sb.toString(), "No Java files selected", Messages.getErrorIcon());
                return null;
            }

            sb.append("\nThe above files will not be processed. Do you want to continue processing the other "+javaFileList.size()+" files?");

            if (Messages.showYesNoDialog(project, sb.toString(), "Skipping non-Java files", Messages.getWarningIcon()) != 0) {
                return null;
            }
        }

        return javaFileList;
    }

    private void processFile(PsiJavaFile psiJavaFile) {
        System.out.println("processing "+psiJavaFile);

        PsiImportList psiImportList = psiJavaFile.getImportList();

        PsiImportStatementBase[] psiImportStatements = psiImportList.getAllImportStatements();
        for (PsiImportStatementBase psiImportStatement : psiImportStatements) {
            PsiJavaCodeReferenceElement psiJavaCodeReferenceElement = psiImportStatement.getImportReference();

            String qualifiedName = psiJavaCodeReferenceElement.getQualifiedName();
            int x = psiJavaCodeReferenceElement.getTextOffset(); // TODO: find why this is wrong - starts after package name, even though length is correct
            int l = psiJavaCodeReferenceElement.getTextLength();
            System.out.println("@,"+x+","+l+",import,"+qualifiedName);
            // TODO: replace through map
        }
    }

    public void actionPerformed(AnActionEvent event) {
        System.out.println("ApplySrg2Source experimental starting");

        project = event.getData(PlatformDataKeys.PROJECT);
        facade = JavaPsiFacade.getInstance(project);

        VirtualFile projectFileDirectory = event.getData(PlatformDataKeys.PROJECT_FILE_DIRECTORY);
        System.out.println("project file directory = "+projectFileDirectory);

        List<PsiJavaFile> psiJavaFiles = getSelectedJavaFiles(event);
        if (psiJavaFiles == null) {
            return;
        }
        System.out.println("Processing "+psiJavaFiles.size()+" files");


        for (PsiJavaFile psiJavaFile: psiJavaFiles) {
            processFile(psiJavaFile);
        }


        /*
        PsiPackage psiPackage = facade.findPackage("agaricus.applysrg.samplepackage");

        PsiClass[] psiClasses = psiPackage.getClasses();
        System.out.println("psiClasses="+psiClasses);

        for (PsiClass psiClass: psiClasses) {
            System.out.println("* "+psiClass.getQualifiedName());
            PsiMethod[] psiMethods = psiClass.getMethods();
            for (PsiMethod psiMethod: psiMethods) {
                System.out.println("- method: "+psiMethod);

                PsiParameterList psiParameterList = psiMethod.getParameterList();
                PsiParameter[] psiParameters = psiParameterList.getParameters();
                PsiCodeBlock psiCodeBlock = psiMethod.getBody();
                PsiElement element = psiCodeBlock.getFirstBodyElement();
                do {
                    element = element.getNextSibling();
                    System.out.println("-- "+element);
                } while(element != null);
            }
            PsiField[] psiFields = psiClass.getFields();
            for (PsiField psiField: psiFields) {
                System.out.println("- field: "+psiField);
            }
        }*/

        List<RenamingClass> classes = new ArrayList<RenamingClass>();
        List<RenamingField> fields = new ArrayList<RenamingField>();
        List<RenamingMethod> methods = new ArrayList<RenamingMethod>();
        List<RenamingMethodParametersList> parametersLists = new ArrayList<RenamingMethodParametersList>();

        if (!SrgLoader.promptAndLoadSrg(project, classes, fields, methods, parametersLists))
            return;


    }
}
