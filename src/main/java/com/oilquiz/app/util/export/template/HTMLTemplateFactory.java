package com.oilquiz.app.util.export.template;

public class HTMLTemplateFactory {

    public static HTMLTemplate createTemplate(String templateName) {
        switch (templateName) {
            case "讲义":
                return new LectureNotesTemplate();
            case "小抄":
                return new CheatSheetTemplate();
            case "打印":
                return new PrintMaterialTemplate();
            case "背诵":
                return new RecitationTemplate();
            case "阅读":
                return new ReadingMaterialTemplate();
            case "记忆":
                return new MemoryCardsTemplate();
            default:
                return new LectureNotesTemplate(); // 默认使用讲义模板
        }
    }
}
