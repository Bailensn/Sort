@Entry
@Component
struct Index {
  @State message: string = 'Sort';

  build() {
    Row() {
      Column({ space: 8 }) {
        Text(this.message)
          .fontSize($r('app.float.page_text_font_size'))
          .fontWeight(FontWeight.Bold)
        Text('OCR 界面待接入')
          .fontSize(14)
          .fontColor('#888888')
      }
      .width('100%')
      .constraintSize({ maxWidth: 720 })
    }
    .height('100%')
  }
}
