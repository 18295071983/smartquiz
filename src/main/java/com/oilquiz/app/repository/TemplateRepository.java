package com.oilquiz.app.repository;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.TemplateDao;
import com.oilquiz.app.model.Template;
import com.oilquiz.app.viewmodel.TemplateViewModel;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TemplateRepository {

    private TemplateDao templateDao;
    private ExecutorService executorService;
    private Handler mainHandler;

    public TemplateRepository(Application application) {
        AppDatabase database = AppDatabase.getDatabase(application);
        templateDao = database.templateDao();
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void getTemplates(final TemplateViewModel.GetTemplatesCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Template> templates = templateDao.getTemplates();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(templates);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void getTemplateById(final long id, final TemplateViewModel.GetTemplateCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Template template = templateDao.getTemplateById(id);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(template);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void addTemplate(final Template template, final TemplateViewModel.AddTemplateCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    templateDao.insert(template);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess();
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void updateTemplate(final Template template, final TemplateViewModel.UpdateTemplateCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    templateDao.update(template);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess();
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void deleteTemplate(final long id, final TemplateViewModel.DeleteTemplateCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    templateDao.deleteTemplate(id);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess();
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }
}
