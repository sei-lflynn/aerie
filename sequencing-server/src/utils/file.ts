import fs from 'fs';
import { getEnv } from '../env.js';

export async function writeFile(fileName: string, folderName: string, data: string): Promise<string> {
  const folder = `${getEnv().STORAGE}/${folderName}/`;

  await fs.promises.mkdir(folder, { recursive: true });

  await fs.promises.writeFile(folder + fileName, data, {
    flag: 'w',
  });

  return folder + fileName;
}
