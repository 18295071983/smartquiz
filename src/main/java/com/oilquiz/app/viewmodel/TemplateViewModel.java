package com.oilquiz.app.viewmodel;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;

import com.oilquiz.app.model.Template;
import com.oilquiz.app.repository.TemplateRepository;

import java.util.List;

public class TemplateViewModel extends AndroidViewModel {

    private TemplateRepository templateRepository;

    public TemplateViewModel(Application application) {
        super(application);
        templateRepository = new TemplateRepository(application);
    }

    public void getTemplates(GetTemplatesCallback callback) {
        templateRepository.getTemplates(callback);
    }

    public void getTemplateById(long id, GetTemplateCallback callback) {
        templateRepository.getTemplateById(id, callback);
    }

    public void addTemplate(Template template, AddTemplateCallback callback) {
        templateRepository.addTemplate(template, callback);
    }

    public void updateTemplate(Template template, UpdateTemplateCallback callback) {
        templateRepository.updateTemplate(template, callback);
    }

    public void deleteTemplate(long id, DeleteTemplateCallback callback) {
        templateRepository.deleteTemplate(id, callback);
    }

    public interface GetTemplatesCallback {
        void onSuccess(List<Template> templates);
        void onFailure(String error);
    }

    public interface GetTemplateCallback {
        void onSuccess(Template template);
        void onFailure(String error);
    }

    public interface AddTemplateCallback {
        void onSuccess();
        void onFailure(String error);
    }

    public interface UpdateTemplateCallback {
        void onSuccess();
        void onFailure(String error);
    }

    public interface DeleteTemplateCallback {
        void onSuccess();
        void onFailure(String error);
    }
}