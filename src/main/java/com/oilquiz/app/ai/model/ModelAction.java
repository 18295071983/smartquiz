package com.oilquiz.app.ai.model;

/**
 * ModelAction - 模型操作枚举
 * 用于表示模型的不同操作类型
 */
public class ModelAction {
    public enum ActionType {
        SELECT,
        DOWNLOAD,
        DELETE,
        CONFIGURE,
        TEST
    }

    private final ActionType type;
    private final Model model;

    private ModelAction(ActionType type, Model model) {
        this.type = type;
        this.model = model;
    }

    public static ModelAction select(Model model) {
        return new ModelAction(ActionType.SELECT, model);
    }

    public static ModelAction download(Model model) {
        return new ModelAction(ActionType.DOWNLOAD, model);
    }

    public static ModelAction delete(Model model) {
        return new ModelAction(ActionType.DELETE, model);
    }

    public static ModelAction configure(Model model) {
        return new ModelAction(ActionType.CONFIGURE, model);
    }

    public static ModelAction test(Model model) {
        return new ModelAction(ActionType.TEST, model);
    }

    public ActionType getType() {
        return type;
    }

    public Model getModel() {
        return model;
    }
}