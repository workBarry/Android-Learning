import { provideZonelessChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app.component';

bootstrapApplication(AppComponent, {
  providers: [provideZonelessChangeDetection()],
}).catch(error => console.error('Legacy Web 啟動失敗', error));
